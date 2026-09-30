package com.stcloud.share;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.share.entity.FileShare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.stcloud.core.service.StorageService;
import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 仅显式指定TASK专用MySQL端口才运行，继承同一分享并发矩阵验证真实InnoDB。 */
@EnabledIfSystemProperty(named="test.mysql.port",matches="60328")
@SpringBootTest(classes=ShareTestApplication.class,properties={
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${test.mysql.port}/stcloud_resume?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
    "spring.datasource.username=root","spring.datasource.password=isolated-resume-mysql-20260928",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=1",
    "spring.sql.init.schema-locations=classpath:mysql-share-fixture.sql"
})
class ShareMysqlIntegrationTest extends ShareServiceImplNoTransactionIntegrationTest {
    @Autowired StorageService storage;
    @Autowired javax.sql.DataSource datasource;
    @Autowired com.stcloud.share.service.ShareBruteForceGuard guard;
    FileShare fixture(String name) {
        var node=insertFileNode(1L,3001L,name,0);node.setStoragePath("isolated/"+name);fileNodeMapper.updateById(node);
        var share=new FileShare();share.setShareCode("MYSQL"+node.getId());share.setFileNodeId(node.getId());share.setCreatorId(3001L);
        share.setShareType(0);share.setPermission(1);share.setPermissions("{\"view\":true,\"download\":true}");share.setAllowDownload(1);
        share.setDownloadCount(0);share.setViewCount(0);share.setStatus(1);share.setDownloadLimit(1);fileShareMapper.insert(share);return share;
    }
    @Test void tc0503BadPasswordAndRequiredCaptchaNeverAccessSource() {
        for(boolean captcha:new boolean[]{false,true}) {
            reset(storage);var share=fixture("password-"+captcha+".txt");share.setShareType(1);share.setPassword("correct");fileShareMapper.updateById(share);
            if(captcha)for(int i=0;i<3;i++)guard.recordFailure("isolated-ip",share.getShareCode());
            if(captcha)assertTrue(guard.needsCaptcha(share.getShareCode()));
            var response=new MockHttpServletResponse();String password=captcha?"correct":"wrong";
            assertThrows(BusinessException.class,()->shareService.streamShareFile(share.getShareCode(),null,password,null,null,response));
            assertThrows(BusinessException.class,()->shareService.getDownloadUrl(share.getShareCode(),null,password,null,null));
            assertEquals(0,response.getContentAsByteArray().length);assertEquals(0,fileShareMapper.selectById(share.getId()).getDownloadCount());verifyNoInteractions(storage);
        }
    }
    @Test void tc0508RealInnoDbLockTimeoutDoesNotGrantOrOutput()throws Exception {
        var share=fixture("lock-timeout.txt");var closed=new AtomicBoolean();
        when(storage.downloadObject(any())).thenAnswer(inv->new FilterInputStream(new ByteArrayInputStream(new byte[]{1,2,3})) {
            @Override public void close()throws IOException{closed.set(true);super.close();}
        });
        try(var blocker=datasource.getConnection()) {
            blocker.setAutoCommit(false);try(var statement=blocker.prepareStatement("UPDATE file_share SET view_count=view_count+1 WHERE id=?")){statement.setLong(1,share.getId());statement.executeUpdate();}
            var response=new MockHttpServletResponse();long start=System.nanoTime();
            assertThrows(RuntimeException.class,()->shareService.streamShareFile(share.getShareCode(),null,null,null,null,response));
            assertTrue(System.nanoTime()-start>=800_000_000L);assertEquals(0,response.getContentAsByteArray().length);assertNull(response.getHeader("Content-Length"));assertTrue(closed.get());blocker.rollback();
        }
        assertEquals(0,fileShareMapper.selectById(share.getId()).getDownloadCount());
        var next=new MockHttpServletResponse();shareService.streamShareFile(share.getShareCode(),null,null,null,null,next);assertArrayEquals(new byte[]{1,2,3},next.getContentAsByteArray());
        assertEquals(1,fileShareMapper.selectById(share.getId()).getDownloadCount());
    }
    @Test void tc0511ActualExpiryDuringSourceOpenAndPasswordSnapshotSemantics() {
        var expired=fixture("expiry-edge.txt");expired.setExpireAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(2));fileShareMapper.updateById(expired);
        // MySQL DATETIME(0)可能舍入纳秒；以实际持久化边界为准，避免在库内尚未到期时误判。
        expired.setExpireAt(fileShareMapper.selectById(expired.getId()).getExpireAt());
        when(storage.downloadObject(any())).thenAnswer(inv->{
            while(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).isBefore(expired.getExpireAt()))Thread.sleep(30);
            return new ByteArrayInputStream(new byte[]{1});
        });
        var response=new MockHttpServletResponse();assertThrows(BusinessException.class,()->shareService.streamShareFile(expired.getShareCode(),null,null,null,null,response));
        assertEquals(0,response.getContentAsByteArray().length);assertEquals(0,fileShareMapper.selectById(expired.getId()).getDownloadCount());
        reset(storage);var changed=fixture("password-inflight.txt");changed.setShareType(1);changed.setPassword("old");fileShareMapper.updateById(changed);
        when(storage.downloadObject(any())).thenAnswer(inv->{changed.setPassword("new");fileShareMapper.updateById(changed);return new ByteArrayInputStream(new byte[]{7});});
        var allowed=new MockHttpServletResponse();shareService.streamShareFile(changed.getShareCode(),null,"old",null,null,allowed);
        assertArrayEquals(new byte[]{7},allowed.getContentAsByteArray());assertEquals(1,fileShareMapper.selectById(changed.getId()).getDownloadCount());
        assertThrows(BusinessException.class,()->shareService.getDownloadUrl(changed.getShareCode(),null,"old",null,null));
    }
    @Test void tc0512LargeStreamPacedAndExternalReadOutsideTransaction() {
        var share=fixture("large-stream.bin");byte[] expected=new byte[6*1024*1024];new java.util.Random(27).nextBytes(expected);
        when(storage.downloadObject(any())).thenAnswer(inv->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new FilterInputStream(new ByteArrayInputStream(expected)) {
                @Override public int read(byte[] b,int off,int len)throws IOException{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return super.read(b,off,len);}
            };
        });
        var response=new MockHttpServletResponse();long start=System.nanoTime();shareService.streamShareFile(share.getShareCode(),null,null,null,null,response);
        assertTrue(System.nanoTime()-start>=1_100_000_000L);assertArrayEquals(expected,response.getContentAsByteArray());assertEquals(1,fileShareMapper.selectById(share.getId()).getDownloadCount());
    }
    @Test void tc0502TwoJvmUrlAndFourStreamsCompeteForExactlyOneGrant()throws Exception {
        var share=fixture("two-process.txt");var ready=new java.util.concurrent.CountDownLatch(4);var release=new java.util.concurrent.CountDownLatch(1);
        when(storage.downloadObject(any())).thenAnswer(inv->{ready.countDown();assertTrue(release.await(20,java.util.concurrent.TimeUnit.SECONDS));return new ByteArrayInputStream(new byte[]{1,2,3});});
        var output=new java.util.concurrent.LinkedBlockingQueue<String>();var logs=new StringBuffer();var pool=java.util.concurrent.Executors.newFixedThreadPool(5);Process child=null;
        try {
            child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ShareInstanceWorker.class.getName(),"60328",share.getShareCode()).redirectErrorStream(true).start();
            var running=child;pool.submit(()->{try(var reader=running.inputReader()){for(String line;(line=reader.readLine())!=null;){logs.append(line).append('\n');if(line.startsWith("PROBE "))output.add(line);}}catch(IOException ignored){}finally{output.add("EOF");}});
            assertEquals("PROBE SOURCE_READY",output.poll(40,java.util.concurrent.TimeUnit.SECONDS),logs.toString());
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{setUpUser(3001L,1L);try {
                var response=new MockHttpServletResponse();try{shareService.streamShareFile(share.getShareCode(),null,null,null,null,response);}
                catch(BusinessException rejected){assertEquals(0,response.getContentAsByteArray().length);return 0;}
                assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());return 1;
            }finally{clearContext();}}));
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));var input=child.outputWriter();input.write("GO\n");input.flush();release.countDown();
            String url=output.poll(15,java.util.concurrent.TimeUnit.SECONDS);assertTrue(java.util.List.of("PROBE GRANTED","PROBE DENIED").contains(url),logs.toString());
            int grants=url.equals("PROBE GRANTED")?1:0;for(var future:futures)grants+=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,grants);assertEquals(1,fileShareMapper.selectById(share.getId()).getDownloadCount());
            assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,child.exitValue());assertFalse(logs.toString().contains("https://isolated-object.invalid"));
        } finally {release.countDown();if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test @EnabledIfSystemProperty(named="test.s3.port",matches="60330")
    void tc0513ActualS3PresignedUrlReusableButApplicationRetryDenied()throws Exception {
        var endpoint=java.net.URI.create("http://127.0.0.1:60330");
        var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("isolated-resume","isolated-resume-s3-secret-20260928"));
        var config=software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build();
        try(var s3=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(endpoint).region(software.amazon.awssdk.regions.Region.US_EAST_1).credentialsProvider(credentials).serviceConfiguration(config).build();
            var signer=software.amazon.awssdk.services.s3.presigner.S3Presigner.builder().endpointOverride(endpoint).region(software.amazon.awssdk.regions.Region.US_EAST_1).credentialsProvider(credentials).serviceConfiguration(config).build()) {
            String bucket="resume-"+java.util.UUID.randomUUID();s3.createBucket(b->b.bucket(bucket));
            byte[] expected="actual S3 content".getBytes(java.nio.charset.StandardCharsets.UTF_8);s3.putObject(b->b.bucket(bucket).key("object"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(expected));
            var share=fixture("presigned-s3.txt");when(storage.generateDownloadUrl(any())).thenAnswer(inv->signer.presignGetObject(r->r.signatureDuration(java.time.Duration.ofMinutes(2)).getObjectRequest(b->b.bucket(bucket).key("object"))).url().toString());
            String granted=shareService.getDownloadUrl(share.getShareCode(),null,null,null,null).getData();
            assertThrows(BusinessException.class,()->shareService.getDownloadUrl(share.getShareCode(),null,null,null,null));
            var http=java.net.http.HttpClient.newHttpClient();for(int i=0;i<2;i++) {
                var response=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(granted)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200,response.statusCode());assertArrayEquals(expected,response.body());
            }
            assertEquals(1,fileShareMapper.selectById(share.getId()).getDownloadCount());
        }
    }
}
