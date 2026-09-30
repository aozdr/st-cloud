package com.stcloud.share;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.service.ShareService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.io.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 独立JVM URL授予请求；源准备后阻塞，由主进程统一释放，与流竞争同一MySQL额度。 */
public class ShareInstanceWorker {
    public static void main(String[] args)throws Exception {
        if(!args[0].equals("60328"))throw new IllegalArgumentException("isolated port required");
        try(var app=new SpringApplicationBuilder(ShareTestApplication.class).profiles("test").run(
            "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "--spring.datasource.url=jdbc:mysql://127.0.0.1:60328/stcloud_resume?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
            "--spring.datasource.username=root","--spring.datasource.password=isolated-resume-mysql-20260928","--spring.sql.init.mode=never")) {
            TenantContext.setTenantId(1L);TenantContext.setTenantMode("SAAS");UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(3001L).tenantId(1L).build());
            var input=new BufferedReader(new InputStreamReader(System.in));
            when(app.getBean(StorageService.class).generateDownloadUrl(any())).thenAnswer(inv->{
                System.out.println("PROBE SOURCE_READY");if(!"GO".equals(input.readLine()))throw new IOException("missing release");
                return "https://isolated-object.invalid/only-if-granted";
            });
            try {var result=app.getBean(ShareService.class).getDownloadUrl(args[1],null,null,null,null);
                if(result.getData()==null)throw new IllegalStateException("missing granted URL");System.out.println("PROBE GRANTED");}
            catch(BusinessException rejected){System.out.println("PROBE DENIED");}
        } finally {UserContext.clear();TenantContext.clear();}
    }
}
