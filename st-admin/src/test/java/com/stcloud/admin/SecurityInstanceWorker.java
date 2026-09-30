package com.stcloud.admin;

import com.stcloud.auth.security.JwtAuthenticationFilter;
import com.stcloud.auth.service.AuthService;
import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 独立JVM实例B；只从stdin接收测试令牌，不将其写日志或命令行。 */
public class SecurityInstanceWorker {
    public static void main(String[] args) throws Exception {
        try (var app = new SpringApplicationBuilder(UserManageSecurityIntegrationTest.App.class,
                UserSecurityRedisIntegrationTest.RedisApp.class).profiles("test").run(
                "--spring.datasource.url=" + args[0], "--spring.sql.init.mode=never",
                "--stcloud.jwt.master-key=isolated-admin-redis-master-key-32-bytes",
                "--test.redis.port=" + args[1], "--spring.main.web-application-type=none")) {
            var filter = new JwtAuthenticationFilter(app.getBean(JwtUtils.class),
                    app.getBean(UserSecurityService.class),app.getBean(StringRedisTemplate.class));
            var input = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("PROBE READY");
            for (String line; (line=input.readLine())!=null;) {
                if (line.equals("STOP")) break;
                String[] command = line.split(" ",2);
                if (command[0].equals("REFRESH")) {
                    boolean allowed;
                    try { app.getBean(AuthService.class).refreshToken(command[1]); allowed=true; }
                    catch (RuntimeException rejected) { allowed=false; }
                    finally { UserContext.clear(); TenantContext.clear(); }
                    System.out.println("PROBE REFRESH " + allowed);
                    continue;
                }
                var request = new MockHttpServletRequest("GET","/api/test/protected");
                request.addHeader("Authorization","Bearer " + command[1]);
                var response = new MockHttpServletResponse();
                boolean inFlight = command[0].equals("INFLIGHT");
                filter.doFilter(request,response,(req,res)-> {
                    boolean allowed = Long.valueOf(101L).equals(UserContext.getUserId());
                    if (inFlight) {
                        System.out.println("PROBE AUTHORIZED " + allowed);
                        if (!"RESUME".equals(input.readLine())) throw new java.io.IOException("missing resume");
                        allowed = allowed && Long.valueOf(101L).equals(UserContext.getUserId());
                    }
                    response.setStatus(allowed?200:401);
                });
                System.out.println("PROBE ACCESS " + (response.getStatus()==200));
                if (UserContext.getCurrentUser()!=null) throw new IllegalStateException("context leaked");
            }
        }
    }
}
