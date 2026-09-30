package com.stcloud.core.service;

import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实 ImageIO + 受控 S3 边界，验证源字节、格式及单实例并发上限。 */
class ThumbnailRendererTest {
    private static final String KEY = "isolated/test.png";

    private static byte[] image() throws IOException {
        BufferedImage input = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(input, "png", out));
        return out.toByteArray();
    }

    private static final class Fixture {
        final StorageService storage = mock(StorageService.class);
        final S3Client s3 = mock(S3Client.class);
        final ThumbnailRenderer renderer;

        Fixture(long bytes, long pixels, int concurrent, long declared) {
            S3StorageConfig config = new S3StorageConfig();
            renderer = new ThumbnailRenderer(storage, s3, config);
            ReflectionTestUtils.setField(renderer, "maxSourceBytes", bytes);
            ReflectionTestUtils.setField(renderer, "maxPixels", pixels);
            ReflectionTestUtils.setField(renderer, "maxConcurrent", concurrent);
            when(s3.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().contentLength(declared).build());
        }

        void initialize() { renderer.initialize(); }
        void bytes(byte[] input) { when(storage.downloadObject(KEY)).thenAnswer(ignored -> new ByteArrayInputStream(input)); }
    }

    @Test
    void jpegOutputIsDecodableAndWithinRequestedDimension() throws Exception {
        byte[] png = image();
        Fixture f = new Fixture(png.length, 768, 1, png.length);
        f.bytes(png); f.initialize();
        byte[] result = f.renderer.render(KEY, "png", 12);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertNotNull(decoded);
        assertTrue(decoded.getWidth() >= 1 && decoded.getWidth() <= 12);
        assertTrue(decoded.getHeight() >= 1 && decoded.getHeight() <= 12);
    }

    @Test
    void allAllowedImageFormatsProduceBoundedJpegAtEachThumbnailSize() throws Exception {
        BufferedImage source = new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB);
        for (String format : new String[]{"jpg", "jpeg", "png", "gif", "bmp"}) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(source, format, encoded), "测试环境缺少格式编码器: " + format);
            byte[] bytes = encoded.toByteArray();
            Fixture f = new Fixture(bytes.length, 24L * 16, 1, bytes.length);
            f.bytes(bytes); f.initialize();
            for (int maxDim : new int[]{150, 400, 1200}) {
                byte[] output = f.renderer.render(KEY, format, maxDim);
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(output));
                assertNotNull(decoded, format + " output");
                assertTrue(decoded.getWidth() >= 1 && decoded.getWidth() <= maxDim);
                assertTrue(decoded.getHeight() >= 1 && decoded.getHeight() <= maxDim);
            }
        }
    }

    @Test
    void declaredSizeChecksBothSidesOfBoundaryBeforeDownloading() throws Exception {
        byte[] png = image();
        for (int offset : new int[] {-1, 0}) {
            Fixture f = new Fixture(png.length - offset, 768, 1, png.length);
            f.bytes(png); f.initialize();
            assertNotNull(ImageIO.read(new ByteArrayInputStream(f.renderer.render(KEY, "png", 24))));
        }
        Fixture tooLarge = new Fixture(png.length - 1, 768, 1, png.length);
        tooLarge.initialize();
        assertThrows(BusinessException.class, () -> tooLarge.renderer.render(KEY, "png", 24));
        verify(tooLarge.storage, never()).downloadObject(any());
    }

    @Test
    void actualStreamAboveLimitStopsAndClosesSourceDespiteSmallHead() throws Exception {
        byte[] png = image();
        Fixture f = new Fixture(png.length - 1, 768, 1, 1);
        AtomicBoolean closed = new AtomicBoolean();
        InputStream source = new FilterInputStream(new ByteArrayInputStream(png)) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        when(f.storage.downloadObject(KEY)).thenReturn(source);
        f.initialize();
        assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "png", 24));
        assertTrue(closed.get());
    }

    @Test
    void missingHeadLengthStillAppliesActualStreamLimit() throws Exception {
        byte[] png = image();
        Fixture f = new Fixture(png.length - 1, 768, 1, 1);
        when(f.s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        f.bytes(png); f.initialize();
        assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "png", 24));
        verify(f.storage).downloadObject(KEY);
    }

    @Test
    void pixelBudgetRejectsOversizeHeaderBeforeFullDecode() throws Exception {
        byte[] png = image();
        Fixture exact = new Fixture(png.length, 32L * 24, 1, png.length);
        exact.bytes(png); exact.initialize();
        assertNotNull(ImageIO.read(new ByteArrayInputStream(exact.renderer.render(KEY, "png", 24))));
        Fixture below = new Fixture(png.length, 32L * 24 - 1, 1, png.length);
        below.bytes(png); below.initialize();
        assertThrows(BusinessException.class, () -> below.renderer.render(KEY, "png", 24));

        byte[] hugeHeader = png.clone();
        for (int offset : new int[]{16, 20}) {
            hugeHeader[offset] = 0;
            hugeHeader[offset + 1] = 1;
            hugeHeader[offset + 2] = 0;
            hugeHeader[offset + 3] = 0;
        }
        CRC32 crc = new CRC32();
        crc.update(hugeHeader, 12, 17);
        long checksum = crc.getValue();
        for (int i = 0; i < 4; i++) hugeHeader[29 + i] = (byte) (checksum >>> (24 - 8 * i));
        Fixture overflow = new Fixture(hugeHeader.length, Integer.MAX_VALUE, 1, hugeHeader.length);
        overflow.bytes(hugeHeader); overflow.initialize();
        assertThrows(BusinessException.class, () -> overflow.renderer.render(KEY, "png", 24),
                "65536² 像素必须由 long 乘法识别并在解码前拒绝");
    }

    @Test
    void unsupportedOrDisguisedInputNeverProducesJpeg() throws Exception {
        byte[] png = image();
        Fixture f = new Fixture(png.length, 768, 1, png.length);
        f.initialize();
        assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "svg", 24));
        assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "webp", 24));
        verifyNoInteractions(f.storage, f.s3);
        f.bytes("<script>alert(1)</script>".getBytes());
        assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "jpg", 24));
    }

    @Test
    void onePermitRejectsConcurrentRenderThenAllowsAnother() throws Exception {
        byte[] png = image();
        Fixture f = new Fixture(png.length, 768, 1, png.length);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(f.storage.downloadObject(KEY)).thenAnswer(ignored -> {
            if (!first.getAndSet(false)) return new ByteArrayInputStream(png);
            return new FilterInputStream(new ByteArrayInputStream(png)) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("barrier timeout");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    return super.read(b, off, len);
                }
            };
        });
        f.initialize();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> running = worker.submit(() -> f.renderer.render(KEY, "png", 24));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "png", 24));
            verify(f.storage, times(1)).downloadObject(KEY);
            release.countDown();
            assertNotNull(ImageIO.read(new ByteArrayInputStream(running.get(5, TimeUnit.SECONDS))));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(f.renderer.render(KEY, "png", 24))));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test
    void readFailureReleasesPermitForNextRequest() throws Exception {
        byte[] png = image();
        Fixture f = new Fixture(png.length, 768, 1, png.length);
        AtomicBoolean closed = new AtomicBoolean();
        when(f.storage.downloadObject(KEY)).thenReturn(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("injected read failure"); }
            @Override public void close() { closed.set(true); }
        }).thenAnswer(ignored -> new ByteArrayInputStream(png));
        f.initialize();
        assertThrows(BusinessException.class, () -> f.renderer.render(KEY, "png", 24));
        assertTrue(closed.get());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(f.renderer.render(KEY, "png", 24))));
    }

    @Test
    void nonpositiveResourceLimitsFailInitialization() {
        for (int dimension = 0; dimension < 3; dimension++) {
            for (int invalid : new int[] {0, -1}) {
                long bytes = dimension == 0 ? invalid : 1;
                long pixels = dimension == 1 ? invalid : 1;
                int concurrent = dimension == 2 ? invalid : 1;
                Fixture f = new Fixture(bytes, pixels, concurrent, 1);
                assertThrows(IllegalStateException.class, f::initialize);
            }
        }
    }

    @Test
    void invalidPropertiesAbortSpringStartupAndCustomLimitTakesEffect() throws Exception {
        String[] keys = {"max-thumbnail-source-bytes", "max-thumbnail-pixels", "max-thumbnail-concurrent-generations"};
        for (String key : keys) {
            for (String invalid : new String[] {"0", "-1"}) {
                contextWith("stcloud.preview." + key + "=" + invalid).run(context ->
                        assertNotNull(context.getStartupFailure()));
            }
        }
        contextWith("stcloud.preview.max-thumbnail-source-bytes=1").run(context -> {
            assertNull(context.getStartupFailure());
            assertThrows(BusinessException.class,
                    () -> context.getBean(ThumbnailRenderer.class).render(KEY, "png", 24));
            verify(context.getBean(StorageService.class), never()).downloadObject(any());
        });
    }

    @Test
    void missingThresholdPropertiesUseBoundedProductionDefaults() {
        contextWith("stcloud.test.fixture=true").run(context -> {
            assertNull(context.getStartupFailure());
            var renderer=context.getBean(ThumbnailRenderer.class);
            assertEquals(20971520L, ReflectionTestUtils.getField(renderer,"maxSourceBytes"));
            assertEquals(16000000L, ReflectionTestUtils.getField(renderer,"maxPixels"));
            assertEquals(2, ReflectionTestUtils.getField(renderer,"maxConcurrent"));
            assertThrows(BusinessException.class, () -> renderer.render(KEY,"svg",24));
            verify(context.getBean(StorageService.class),never()).downloadObject(any());
        });
    }

    private ApplicationContextRunner contextWith(String property) {
        StorageService storage = mock(StorageService.class);
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(2L).build());
        return new ApplicationContextRunner()
                .withPropertyValues(property)
                .withBean(StorageService.class, () -> storage)
                .withBean(S3Client.class, () -> client)
                .withBean(ThumbnailRenderer.class, () -> new ThumbnailRenderer(storage, client, new S3StorageConfig()));
    }
}
