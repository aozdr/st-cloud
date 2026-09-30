package com.stcloud.sync.ws;

import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 随机本地端口上的真实HTTP升级/WS帧，安全状态来自独立H2主库。 */
@SpringBootTest(classes = WebSocketSecurityIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ws-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:security-fixture.sql",
        "stcloud.jwt.master-key=isolated-websocket-test-master-key-32-bytes",
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.configuration.map-underscore-to-camel-case=true"})
class WebSocketSecurityIntegrationTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @MapperScan({"com.stcloud.auth.mapper", "com.stcloud.common.mapper"})
    @Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class, JwtUtils.class, UserSecurityService.class,
            SyncWebSocketConfig.class, SyncWebSocketHandler.class, SyncAuthHandshakeInterceptor.class})
    static class App { }
    @LocalServerPort int port;
    @Autowired JwtUtils jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncWebSocketHandler handler;

    @BeforeEach void prepare() {
        jdbc.update("UPDATE sys_user SET status=1,deleted=0,security_version=0 WHERE id=1");
        jdbc.update("UPDATE sys_tenant SET status=1,deleted=0 WHERE id=1");
    }

    String token(String type, boolean version, long expiry) {
        var claims = new HashMap<String,Object>();
        claims.put("type",type); claims.put("userId",1L); claims.put("tenantId",1L);
        if (version) claims.put("securityVersion",0L);
        return io.jsonwebtoken.Jwts.builder().claims(claims).subject("admin").expiration(new Date(expiry))
                .signWith((javax.crypto.SecretKey) ReflectionTestUtils.getField(jwt,"signingKey")).compact();
    }
    static class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            messages.add(data.toString()); socket.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { closed.complete(status); return null; }
        public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
    WebSocket connect(String token, Client client) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer " + token)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/sync/ws"), client).get(10,TimeUnit.SECONDS);
    }
    void trigger(WebSocket socket, String direction) throws Exception {
        if (direction.equals("receive")) socket.sendText("ping",true).get(5,TimeUnit.SECONDS);
        else if (direction.equals("user")) assertEquals(0,handler.sendToUser(1L,"forbidden"));
        else assertEquals(0,handler.sendToTenantUser(1L,1L,"forbidden"));
    }
    void assertClosed(Client client) throws Exception {
        assertEquals(1003,client.closed.get(5,TimeUnit.SECONDS));
        assertNull(client.messages.poll());
        assertEquals(0,handler.getSessionCount(1L));
    }
    @ParameterizedTest @ValueSource(strings={"receive","user","tenant"})
    void tc0119RevocationClosesActualConnectionBeforeBusinessMessage(String direction) throws Exception {
        var client = new Client(); var socket = connect(token("access",true,System.currentTimeMillis()+60000),client);
        try {
            socket.sendText("ping",true).get(5,TimeUnit.SECONDS);
            assertEquals("pong",client.messages.poll(5,TimeUnit.SECONDS));
            assertEquals(1,handler.getSessionCount(1L));
            // 独立提交后的下一次业务收发必须重新读取安全版本。
            jdbc.update("UPDATE sys_user SET security_version=security_version+1 WHERE id=1");
            trigger(socket,direction); assertClosed(client);
        } finally { socket.abort(); }
    }
    @ParameterizedTest @ValueSource(strings={"receive","user","tenant"})
    void tc0120ExpiredConnectionCannotKeepAccess(String direction) throws Exception {
        String access = token("access",true,System.currentTimeMillis()+3000);
        var client = new Client(); var socket = connect(access,client);
        try {
            socket.sendText("ping",true).get(5,TimeUnit.SECONDS);
            assertEquals("pong",client.messages.poll(5,TimeUnit.SECONDS));
            long wait = jwt.parseToken(access).getExpiration().getTime()-System.currentTimeMillis()+20;
            if (wait>0) Thread.sleep(wait);
            trigger(socket,direction); assertClosed(client);
        } finally { socket.abort(); }
    }
    @ParameterizedTest @ValueSource(strings={"expired","missing-version","refresh","download","editor","unknown"})
    void tc0109And20InvalidHandshakeRejected(String variant) {
        String type = List.of("expired","missing-version").contains(variant) ? "access" : variant;
        String access = token(type,!variant.equals("missing-version"),System.currentTimeMillis()+(variant.equals("expired")?-2000:60000));
        var error = assertThrows(java.util.concurrent.ExecutionException.class,()->connect(access,new Client()));
        assertInstanceOf(java.net.http.WebSocketHandshakeException.class,error.getCause());
        assertEquals(0,handler.getSessionCount(1L));
    }
}
