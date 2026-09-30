package com.stcloud.core.service;

import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 限制源字节、解码像素和并发量的图片缩略图渲染器。 */
@Component
@RequiredArgsConstructor
public class ThumbnailRenderer {
    public static final Set<String> FORMATS = Set.of("jpg", "jpeg", "png", "gif", "bmp");
    private final StorageService storageService;
    private final S3Client s3Client;
    private final S3StorageConfig storageConfig;

    @Value("${stcloud.preview.max-thumbnail-source-bytes:20971520}")
    private long maxSourceBytes;
    @Value("${stcloud.preview.max-thumbnail-pixels:16000000}")
    private long maxPixels;
    @Value("${stcloud.preview.max-thumbnail-concurrent-generations:2}")
    private int maxConcurrent;
    private Semaphore permits;

    @PostConstruct
    public void initialize() {
        if (maxSourceBytes <= 0 || maxPixels <= 0 || maxConcurrent <= 0) {
            throw new IllegalStateException("缩略图资源上限必须大于零");
        }
        permits = new Semaphore(maxConcurrent);
    }

    public byte[] render(String storagePath, String suffix, int maxDim) {
        if (!FORMATS.contains(suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) || maxDim <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：图片格式不支持");
        }
        if (!permits.tryAcquire()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缩略图生成繁忙，请稍后重试");
        }
        Path temporary = null;
        try {
            Long declaredSize = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket()).key(storagePath).build()).contentLength();
            // HEAD 长度可能缺失；它仅用于提前拒绝，真实源流仍由下面的字节硬上限保护。
            if (declaredSize != null && declaredSize > maxSourceBytes) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：图片过大");
            }
            temporary = Files.createTempFile("st-thumbnail-", ".image");
            // 元数据可缺失或变化，因此流式拷贝仍设置独立硬上限。
            try (InputStream source = storageService.downloadObject(storagePath);
                 java.io.OutputStream sink = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = source.read(buffer)) != -1) {
                    total += read;
                    if (total > maxSourceBytes) throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：图片过大");
                    sink.write(buffer, 0, read);
                }
            }
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(temporary.toFile())) {
                if (imageInput == null) throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：无图片解码器");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!FORMATS.contains(format)) throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：实际格式不支持");
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > maxPixels) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：像素超限");
                    }
                    ImageReadParam param = reader.getDefaultReadParam();
                    int subsampling = Math.max(1, Math.max(width, height) / Math.max(maxDim, 1));
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    BufferedImage original = reader.read(0, param);
                    if (original == null) throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图");
                    double scale = Math.min(1d, (double) maxDim / Math.max(original.getWidth(), original.getHeight()));
                    int outWidth = Math.max(1, (int) Math.round(original.getWidth() * scale));
                    int outHeight = Math.max(1, (int) Math.round(original.getHeight() * scale));
                    BufferedImage thumbnail = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = thumbnail.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(original, 0, 0, outWidth, outHeight, null);
                    } finally {
                        graphics.dispose();
                    }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!ImageIO.write(thumbnail, "jpg", output)) throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图");
                    return output.toByteArray();
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "缩略图生成失败");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
            permits.release();
        }
    }
}
