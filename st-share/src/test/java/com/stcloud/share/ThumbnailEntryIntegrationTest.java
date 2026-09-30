package com.stcloud.share;

import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.*;
import com.stcloud.preview.service.impl.PreviewServiceImpl;
import com.stcloud.share.entity.FileShare;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 三入口共用真实渲染器；分享/节点真实H2，历史元数据与S3故障受控，JPEG真实解码。 */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ThumbnailEntryIntegrationTest extends AbstractShareIntegrationTest {
    @Autowired StorageService storage;
    @Autowired FileService files;
    @Autowired ThumbnailRenderer bridge;
    S3Client s3;S3Presigner presigner;ThumbnailRenderer renderer;PreviewServiceImpl preview;
    final Map<String,byte[]> source=new ConcurrentHashMap<>(),cache=new ConcurrentHashMap<>();
    final Map<String,Long> declared=new ConcurrentHashMap<>();
    final AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger(),readBytes=new AtomicInteger();
    FileNode node;FileVersion version;FileShare share;long byteLimit=4096;
    volatile RuntimeException cacheFailure,putFailure;
    volatile boolean missingLength,readFailure;
    String selectedSource="current";
    static byte[] picture(String format,int color) throws Exception {
        var image=new BufferedImage(24,16,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<16;y++)for(int x=0;x<24;x++)image.setRGB(x,y,color);
        var out=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,out));return out.toByteArray();
    }
    @BeforeEach void prepare() throws Exception {
        setUpUser(7001L,1L);reset(storage,files,bridge);s3=mock(S3Client.class);presigner=mock(S3Presigner.class);
        node=insertFileNode(1L,7001L,"image.png",0);node.setStoragePath("current");node.setFileSize(1L);fileNodeMapper.updateById(node);
        version=new FileVersion();version.setId(991L);version.setFileNodeId(node.getId());version.setVersionNum(2);version.setStoragePath("history");
        share=new FileShare();share.setShareCode("thumb"+node.getId());share.setFileNodeId(node.getId());share.setCreatorId(7001L);
        share.setShareType(0);share.setPermission(1);share.setPermissions("{\"view\":true,\"download\":true}");share.setAllowDownload(1);
        share.setDownloadCount(0);share.setViewCount(0);share.setStatus(1);fileShareMapper.insert(share);
        source.put("current",picture("png",0xFF0000));source.put("history",picture("png",0x0000FF));
        var config=new S3StorageConfig();config.setBucket("source");config.setPreviewBucket("preview");
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(inv->{
            HeadObjectRequest request=inv.getArgument(0);
            if(request.bucket().equals("preview")) {
                if(cacheFailure!=null)throw cacheFailure;
                if(!cache.containsKey(request.key()))throw S3Exception.builder().statusCode(404).message("fixture cache miss").build();
                return HeadObjectResponse.builder().contentLength((long)cache.get(request.key()).length).build();
            }
            return HeadObjectResponse.builder().contentLength(missingLength?null:declared.getOrDefault(request.key(),(long)source.get(request.key()).length)).build();
        });
        when(storage.downloadObject(anyString())).thenAnswer(inv->{
            opened.incrementAndGet();byte[] bytes=source.get(inv.getArgument(0));
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                @Override public int read(byte[] b,int off,int len)throws IOException {if(readFailure)throw new IOException("fixture source read");int n=super.read(b,off,len);if(n>0)readBytes.addAndGet(n);return n;}
                @Override public void close()throws IOException{closed.incrementAndGet();super.close();}
            };
        });
        when(storage.generateDownloadUrl(anyString())).thenAnswer(inv->"https://authorized.invalid/"+inv.getArgument(0));
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(inv->{
            if(putFailure!=null)throw putFailure;PutObjectRequest request=inv.getArgument(0);RequestBody body=inv.getArgument(1);
            try(var stream=body.contentStreamProvider().newStream()){cache.put(request.key(),stream.readAllBytes());}
            assertEquals("image/jpeg",request.contentType());return PutObjectResponse.builder().build();
        });
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv->{GetObjectRequest request=inv.getArgument(0);
            return new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream(cache.get(request.key())));});
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(inv->{var value=mock(PresignedGetObjectRequest.class);GetObjectPresignRequest request=inv.getArgument(0);
            when(value.url()).thenReturn(new java.net.URL("https://preview.invalid/"+request.getObjectRequest().key()));return value;});
        configureRenderer(4096,16000000,2);
        Object target=AopTestUtils.getTargetObject(shareService);ReflectionTestUtils.setField(target,"s3Client",s3);ReflectionTestUtils.setField(target,"s3StorageConfig",config);
        when(bridge.render(anyString(),anyString(),anyInt())).thenAnswer(inv->renderer.render(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2)));
        preview=new PreviewServiceImpl();ReflectionTestUtils.setField(preview,"fileNodeMapper",fileNodeMapper);ReflectionTestUtils.setField(preview,"fileService",files);
        var versions=mock(FileVersionMapper.class);when(versions.selectById(991L)).thenReturn(version);ReflectionTestUtils.setField(preview,"fileVersionMapper",versions);
        ReflectionTestUtils.setField(preview,"storageService",storage);ReflectionTestUtils.setField(preview,"s3Client",s3);ReflectionTestUtils.setField(preview,"s3Presigner",presigner);
        ReflectionTestUtils.setField(preview,"s3StorageConfig",config);ReflectionTestUtils.setField(preview,"thumbnailRenderer",renderer);
    }
    void configureRenderer(long bytes,long pixels,int concurrent) {
        byteLimit=bytes;var config=new S3StorageConfig();config.setBucket("source");config.setPreviewBucket("preview");
        renderer=new ThumbnailRenderer(storage,s3,config);ReflectionTestUtils.setField(renderer,"maxSourceBytes",bytes);
        ReflectionTestUtils.setField(renderer,"maxPixels",pixels);ReflectionTestUtils.setField(renderer,"maxConcurrent",concurrent);renderer.initialize();
        if(preview!=null)ReflectionTestUtils.setField(preview,"thumbnailRenderer",renderer);
    }
    byte[] invoke(String entry,String size) {
        selectedSource=entry.equals("history")?"history":"current";
        if(entry.equals("share")){var response=new MockHttpServletResponse();shareService.streamShareThumbnail(share.getShareCode(),null,size,null,null,null,response);return response.getContentAsByteArray();}
        String url;
        if(entry.equals("history")){var result=preview.previewVersion(node.getId(),991L);if(result.getType().equals("unsupported"))throw new BusinessException(ResultCode.BAD_REQUEST,"unsupported");url=result.getUrl();}
        else url=preview.getThumbnailUrl(node.getId(),size);
        return cache.get(url.substring("https://preview.invalid/".length()));
    }
    void empty() {cache.clear();clearInvocations(s3,storage);opened.set(0);closed.set(0);readBytes.set(0);}
    void assertJpeg(byte[] bytes,int dimension) throws Exception {
        assertNotNull(bytes);assertEquals(0xff,bytes[0]&255);assertEquals(0xd8,bytes[1]&255);
        var decoded=ImageIO.read(new ByteArrayInputStream(bytes));assertNotNull(decoded);assertTrue(decoded.getWidth()>0&&decoded.getWidth()<=dimension);assertTrue(decoded.getHeight()>0&&decoded.getHeight()<=dimension);
    }
    @ParameterizedTest @ValueSource(strings={"normal","history","share"})
    void tc0701AllFormatsAcrossEachSupportedSize(String entry) throws Exception {
        for(String format:List.of("jpg","jpeg","png","gif","bmp"))for(String size:entry.equals("normal")?List.of("sm","md","lg"):List.of(entry.equals("share")?"sm":"lg")) {
            empty();node.setSuffix(format);fileNodeMapper.updateById(node);source.put("current",picture(format,0xFF0000));source.put("history",picture(format,0x0000FF));
            assertJpeg(invoke(entry,size),size.equals("sm")?150:size.equals("md")?400:1200);assertEquals(opened.get(),closed.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"normal","history","share"})
    void tc0703And04ByteBoundariesAndUntrustedHead(String entry) throws Exception {
        String key=entry.equals("history")?"history":"current";byte[] original=source.get(key);int limit=1024;configureRenderer(limit,16000000,1);
        for(int length:new int[]{limit-1,limit,limit+1}) {
            empty();source.put(key,Arrays.copyOf(original,length));
            if(length<=limit)assertJpeg(invoke(entry,"sm"),entry.equals("history")?1200:150);
            else {assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertEquals(0,opened.get());assertTrue(cache.isEmpty());}
        }
        source.put(key,Arrays.copyOf(original,limit+20000));
        for(boolean absent:new boolean[]{false,true}) {
            empty();missingLength=absent;declared.put(key,1L);
            assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertTrue(cache.isEmpty());assertEquals(1,closed.get());assertTrue(readBytes.get()<=limit+8192);
        }
        missingLength=false;declared.clear();source.put(key,original);assertJpeg(invoke(entry,"sm"),entry.equals("history")?1200:150);
        empty();doAnswer(inv->new FilterInputStream(new SequenceInputStream(new ByteArrayInputStream(original),new ByteArrayInputStream(new byte[limit+20000]))) {
            @Override public int read(byte[] bytes,int off,int length)throws IOException {int n=super.read(bytes,off,length);if(n>0)readBytes.addAndGet(n);return n;}
            @Override public void close()throws IOException{closed.incrementAndGet();super.close();}
        }).when(storage).downloadObject(key);
        assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertTrue(cache.isEmpty());assertEquals(1,closed.get());assertTrue(readBytes.get()<=limit+8192);
    }
    @ParameterizedTest @ValueSource(strings={"normal","history","share"})
    void tc0706And08InvalidSourcesReadAndPutFailureRecover(String entry) throws Exception {
        String key=entry.equals("history")?"history":"current";byte[] original=source.get(key);
        for(byte[] broken:List.of("not an image".getBytes(),Arrays.copyOf(original,12),Arrays.copyOf(original,original.length/2))) {
            empty();source.put(key,broken);assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertTrue(cache.isEmpty());assertEquals(opened.get(),closed.get());
        }
        source.put(key,original);readFailure=true;empty();assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertTrue(cache.isEmpty());assertEquals(opened.get(),closed.get());
        readFailure=false;putFailure=S3Exception.builder().statusCode(503).message("fixture PUT failure").build();
        assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertTrue(cache.isEmpty());putFailure=null;assertJpeg(invoke(entry,"sm"),entry.equals("history")?1200:150);
    }
    @ParameterizedTest @ValueSource(strings={"normal","history","share"})
    void tc0715CacheHitAndOnly404Regenerates(String entry) throws Exception {
        assertJpeg(invoke(entry,"sm"),entry.equals("history")?1200:150);clearInvocations(storage);opened.set(0);
        assertJpeg(invoke(entry,"sm"),entry.equals("history")?1200:150);assertEquals(0,opened.get());
        for(RuntimeException fault:List.of(S3Exception.builder().statusCode(403).message("fixture denied").build(),new IllegalStateException("fixture timeout"))) {
            cacheFailure=fault;assertThrows(RuntimeException.class,()->invoke(entry,"sm"));assertEquals(0,opened.get());verify(storage,never()).generateDownloadUrl(anyString());
        }
        cacheFailure=null;cache.clear();assertJpeg(invoke(entry,"sm"),entry.equals("history")?1200:150);assertEquals(1,opened.get());
    }
    @Test void tc0710HistoricalObjectControlsLimitsAndReturnedContent() throws Exception {
        source.put("history",new byte[5000]);assertThrows(BusinessException.class,()->invoke("history","lg"));assertTrue(cache.isEmpty());
        assertJpeg(invoke("normal","lg"),1200);empty();source.put("current",new byte[5000]);source.put("history",picture("png",0x0000FF));
        var decoded=ImageIO.read(new ByteArrayInputStream(invoke("history","lg")));assertTrue((decoded.getRGB(0,0)&255)>200);assertTrue(((decoded.getRGB(0,0)>>16)&255)<30);
        verify(storage,never()).downloadObject("current");assertThrows(BusinessException.class,()->invoke("normal","lg"));
    }
    @Test void tc0702WebpAndSvgMainPreviewUseAuthorizedObjectWithoutDecode() {
        for(String suffix:List.of("webp","svg")) {
            node.setSuffix(suffix);fileNodeMapper.updateById(node);
            assertThrows(BusinessException.class,()->preview.getThumbnailUrl(node.getId(),"sm"));
            assertEquals("https://authorized.invalid/current",preview.preview(node.getId()).getUrl());
            assertEquals("https://authorized.invalid/history",preview.previewVersion(node.getId(),991L).getUrl());
        }
        verifyNoInteractions(s3);verify(storage,never()).downloadObject(anyString());verify(files,times(6)).validateAccessible(node.getId());
    }
    @Test void tc0712ThumbnailNeverConsumesDownloadButMainStreamDoes() throws Exception {
        byte[] original=source.get("current");assertJpeg(invoke("share","sm"),150);assertEquals(0,fileShareMapper.selectById(share.getId()).getDownloadCount());
        for(String failure:List.of("webp","svg","huge","broken")) {
            empty();node.setSuffix(failure.equals("webp")||failure.equals("svg")?failure:"png");fileNodeMapper.updateById(node);
            source.put("current",failure.equals("huge")?new byte[5000]:failure.equals("broken")?new byte[10]:original);
            assertThrows(BusinessException.class,()->invoke("share","sm"));assertTrue(cache.isEmpty());assertEquals(0,fileShareMapper.selectById(share.getId()).getDownloadCount());
        }
        source.put("current",original);node.setFileSize((long)original.length);fileNodeMapper.updateById(node);
        var response=new MockHttpServletResponse();shareService.streamShareFile(share.getShareCode(),null,null,null,null,response);
        assertArrayEquals(original,response.getContentAsByteArray());assertEquals(1,fileShareMapper.selectById(share.getId()).getDownloadCount());
    }
    @Test void tc0711UnauthorizedPreviewAndShareNeverReachStorage() {
        doThrow(new BusinessException(ResultCode.FORBIDDEN)).when(files).validateAccessible(node.getId());
        assertThrows(BusinessException.class,()->preview.getThumbnailUrl(node.getId(),"sm"));assertThrows(BusinessException.class,()->preview.previewVersion(node.getId(),991L));
        reset(files);share.setShareType(1);share.setPassword("fixture-code");fileShareMapper.updateById(share);
        assertThrows(BusinessException.class,()->invoke("share","sm"));share.setShareType(0);share.setAllowDownload(0);fileShareMapper.updateById(share);
        assertThrows(BusinessException.class,()->invoke("share","sm"));share.setAllowDownload(1);fileShareMapper.updateById(share);
        var outside=insertFileNode(1L,7001L,"outside.png",0);
        assertThrows(BusinessException.class,()->shareService.streamShareThumbnail(share.getShareCode(),outside.getId(),"sm",null,null,null,new MockHttpServletResponse()));
        verifyNoInteractions(s3,storage,presigner);assertTrue(cache.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"normal","history","share"})
    void tc0701GifUsesOnlyFirstFrame(String entry) throws Exception {
        var writer=ImageIO.getImageWritersByFormatName("gif").next();var bytes=new ByteArrayOutputStream();
        try(var output=ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);writer.prepareWriteSequence(null);
            for(int color:new int[]{0xFF0000,0x0000FF})writer.writeToSequence(new javax.imageio.IIOImage(ImageIO.read(new ByteArrayInputStream(picture("png",color))),null,null),null);
            writer.endWriteSequence();
        } finally {writer.dispose();}
        node.setSuffix("gif");fileNodeMapper.updateById(node);source.put("current",bytes.toByteArray());source.put("history",bytes.toByteArray());
        var decoded=ImageIO.read(new ByteArrayInputStream(invoke(entry,"sm")));assertTrue(((decoded.getRGB(0,0)>>16)&255)>200);assertTrue((decoded.getRGB(0,0)&255)<30);
    }
    @Test void tc0716ThreeEntriesShareOneInstanceLimitAndRecover() throws Exception {
        configureRenderer(4096,16000000,2);
        // 将夹具提交给独立请求线程；数据库仅本测试H2，线程不共享事务上下文。
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();org.springframework.test.context.transaction.TestTransaction.end();
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var active=new AtomicInteger();var peak=new AtomicInteger();
        doAnswer(inv->{
            int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);entered.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture barrier timeout");
            return new FilterInputStream(new ByteArrayInputStream(source.get(inv.getArgument(0)))) {
                @Override public void close()throws IOException {super.close();active.decrementAndGet();}
            };
        }).when(storage).downloadObject(anyString());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var normal=pool.submit(()->{setUpUser(7001L,1L);try{return invoke("normal","lg");}finally{clearContext();}});
            var history=pool.submit(()->{setUpUser(7001L,1L);try{return invoke("history","lg");}finally{clearContext();}});
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            long start=System.nanoTime();assertThrows(BusinessException.class,()->invoke("share","sm"));
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);assertEquals(2,peak.get());
            release.countDown();assertJpeg(normal.get(10,java.util.concurrent.TimeUnit.SECONDS),1200);assertJpeg(history.get(10,java.util.concurrent.TimeUnit.SECONDS),1200);
            assertJpeg(invoke("share","sm"),150);assertEquals(0,active.get());assertEquals(0,fileShareMapper.selectById(share.getId()).getDownloadCount());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    static volatile int probeWidth,probeHeight,probeReads,probeDisposed;
    @Test void tc0716TwoIndependentRendererInstancesMayDuplicateButCacheStaysComplete()throws Exception {
        configureRenderer(4096,16000000,1);var first=renderer;
        var config=new S3StorageConfig();config.setBucket("source");var second=new ThumbnailRenderer(storage,s3,config);
        ReflectionTestUtils.setField(second,"maxSourceBytes",4096L);ReflectionTestUtils.setField(second,"maxPixels",16000000L);
        ReflectionTestUtils.setField(second,"maxConcurrent",1);second.initialize();
        var another=new PreviewServiceImpl();
        for(String field:List.of("fileNodeMapper","fileVersionMapper","fileService","storageService","s3Client","s3Presigner","s3StorageConfig"))
            ReflectionTestUtils.setField(another,field,ReflectionTestUtils.getField(preview,field));
        ReflectionTestUtils.setField(another,"thumbnailRenderer",second);
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();org.springframework.test.context.transaction.TestTransaction.end();
        var bothMiss=new java.util.concurrent.CyclicBarrier(2);var bothRead=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(inv->{HeadObjectRequest request=inv.getArgument(0);
            if(request.bucket().equals("preview")){bothMiss.await(10,java.util.concurrent.TimeUnit.SECONDS);throw S3Exception.builder().statusCode(404).build();}
            return HeadObjectResponse.builder().contentLength((long)source.get(request.key()).length).build();
        }).when(s3).headObject(any(HeadObjectRequest.class));
        doAnswer(inv->{
            bothRead.await(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(first,"permits")).availablePermits());
            assertEquals(0,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(second,"permits")).availablePermits());
            return new ByteArrayInputStream(source.get(inv.getArgument(0)));
        }).when(storage).downloadObject(anyString());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{setUpUser(7001L,1L);try{return preview.getThumbnailUrl(node.getId(),"sm");}finally{clearContext();}});
            var two=pool.submit(()->{setUpUser(7001L,1L);try{return another.getThumbnailUrl(node.getId(),"sm");}finally{clearContext();}});
            assertEquals(one.get(15,java.util.concurrent.TimeUnit.SECONDS),two.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,cache.size());assertJpeg(cache.values().iterator().next(),150);verify(s3,times(2)).putObject(any(PutObjectRequest.class),any(RequestBody.class));
            assertEquals(1,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(first,"permits")).availablePermits());
            assertEquals(1,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(second,"permits")).availablePermits());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    static volatile String probeFault="";
    static volatile javax.imageio.stream.ImageInputStream probeInput;
    public static class ProbeSpi extends javax.imageio.spi.ImageReaderSpi {
        public ProbeSpi(){names=new String[]{"png"};inputTypes=new Class[]{javax.imageio.stream.ImageInputStream.class};pluginClassName=ProbeReader.class.getName();}
        @Override public boolean canDecodeInput(Object value)throws IOException {
            if(!(value instanceof javax.imageio.stream.ImageInputStream stream))return false;long position=stream.getStreamPosition();
            try{return stream.readInt()==0x53545052;}catch(java.io.EOFException ignored){return false;}finally{stream.seek(position);}
        }
        @Override public javax.imageio.ImageReader createReaderInstance(Object extension){return new ProbeReader(this);}
        @Override public String getDescription(Locale locale){return "isolated thumbnail probe";}
    }
    public static class ProbeReader extends javax.imageio.ImageReader {
        ProbeReader(javax.imageio.spi.ImageReaderSpi spi){super(spi);}
        @Override public String getFormatName(){return probeFault.equals("format")?"wbmp":"png";}
        @Override public int getNumImages(boolean search){return 1;}
        @Override public int getWidth(int index)throws IOException{if(probeFault.equals("size"))throw new IOException("fixture dimensions");return probeWidth;}
        @Override public int getHeight(int index){return probeHeight;}
        @Override public Iterator<javax.imageio.ImageTypeSpecifier> getImageTypes(int index){return List.of(javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)).iterator();}
        @Override public javax.imageio.metadata.IIOMetadata getStreamMetadata(){return null;}
        @Override public javax.imageio.metadata.IIOMetadata getImageMetadata(int index){return null;}
        @Override public BufferedImage read(int index,javax.imageio.ImageReadParam param)throws IOException {
            assertEquals(0,index);probeReads++;if(probeFault.equals("decode"))throw new IOException("fixture decode");return new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
        }
        @Override public void dispose(){probeDisposed++;probeInput=(javax.imageio.stream.ImageInputStream)getInput();super.dispose();}
    }
    public static class FailingWriterSpi extends javax.imageio.spi.ImageWriterSpi {
        public FailingWriterSpi(){names=new String[]{"jpg","jpeg"};outputTypes=new Class[]{javax.imageio.stream.ImageOutputStream.class};pluginClassName=FailingWriter.class.getName();}
        @Override public boolean canEncodeImage(javax.imageio.ImageTypeSpecifier type){return true;}
        @Override public javax.imageio.ImageWriter createWriterInstance(Object extension){return new FailingWriter(this);}
        @Override public String getDescription(Locale locale){return "isolated encode failure";}
    }
    public static class FailingWriter extends javax.imageio.ImageWriter {
        FailingWriter(javax.imageio.spi.ImageWriterSpi spi){super(spi);}
        @Override public javax.imageio.metadata.IIOMetadata getDefaultStreamMetadata(javax.imageio.ImageWriteParam param){return null;}
        @Override public javax.imageio.metadata.IIOMetadata getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier type,javax.imageio.ImageWriteParam param){return null;}
        @Override public javax.imageio.metadata.IIOMetadata convertStreamMetadata(javax.imageio.metadata.IIOMetadata metadata,javax.imageio.ImageWriteParam param){return null;}
        @Override public javax.imageio.metadata.IIOMetadata convertImageMetadata(javax.imageio.metadata.IIOMetadata metadata,javax.imageio.ImageTypeSpecifier type,javax.imageio.ImageWriteParam param){return null;}
        @Override public void write(javax.imageio.metadata.IIOMetadata metadata,javax.imageio.IIOImage image,javax.imageio.ImageWriteParam param)throws IOException{throw new IOException("fixture JPEG encoding");}
    }
    Set<String> temporaryFiles()throws IOException {
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))){
            return files.filter(p->p.getFileName().toString().startsWith("st-thumbnail-")).map(Object::toString).collect(java.util.stream.Collectors.toSet());
        }
    }
    @ParameterizedTest @ValueSource(strings={"normal","history","share"})
    void tc0705And08ReaderProbeBeforeDecodeAndResourcesOnEveryFault(String entry)throws Exception {
        var registry=javax.imageio.spi.IIORegistry.getDefaultInstance();var spi=new ProbeSpi();registry.registerServiceProvider(spi);
        String key=entry.equals("history")?"history":"current";source.put(key,new byte[]{0x53,0x54,0x50,0x52});configureRenderer(4096,100,1);
        try {
            for(int width:new int[]{99,100,101,0,-1,Integer.MAX_VALUE}) {
                empty();probeWidth=width;probeHeight=width==Integer.MAX_VALUE?Integer.MAX_VALUE:1;probeFault="";probeReads=0;probeDisposed=0;
                var temporary=temporaryFiles();
                if(width==99||width==100){assertJpeg(invoke(entry,"sm"),1200);assertEquals(1,probeReads);}
                else{assertThrows(BusinessException.class,()->invoke(entry,"sm"));assertEquals(0,probeReads);assertTrue(cache.isEmpty());}
                assertEquals(1,probeDisposed);assertThrows(IOException.class,()->probeInput.read());assertEquals(temporary,temporaryFiles());
            }
            for(String fault:List.of("format","size","decode","encode")) {
                empty();probeWidth=10;probeHeight=10;probeFault=fault;probeReads=0;probeDisposed=0;var temporary=temporaryFiles();
                var failing=new FailingWriterSpi();
                if(fault.equals("encode")) {
                    registry.registerServiceProvider(failing);var writers=registry.getServiceProviders(javax.imageio.spi.ImageWriterSpi.class,true);
                    while(writers.hasNext()){var writer=writers.next();if(writer!=failing)registry.setOrdering(javax.imageio.spi.ImageWriterSpi.class,failing,writer);}
                }
                try{assertThrows(BusinessException.class,()->invoke(entry,"sm"));}finally{if(fault.equals("encode"))registry.deregisterServiceProvider(failing);}
                assertTrue(cache.isEmpty());assertEquals(1,probeDisposed);assertEquals(opened.get(),closed.get());assertThrows(IOException.class,()->probeInput.read());
                assertEquals(temporary,temporaryFiles());assertEquals(1,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(renderer,"permits")).availablePermits());
                probeFault="";assertJpeg(invoke(entry,"sm"),1200);
            }
        } finally {registry.deregisterServiceProvider(spi);probeFault="";}
    }
}
