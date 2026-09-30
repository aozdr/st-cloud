import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.service.FileService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewDeleteProbe {
    public static void main(String[] args) throws Exception {
        Class<?> flowConfig = Class.forName("com.stcloud.core.service.impl.FileServiceFlowIntegrationTest$FlowTestConfig");
        try (var context = new SpringApplicationBuilder(CoreTestApplication.class, flowConfig)
                .profiles("test").web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--logging.level.root=ERROR")) {
            TenantContext.setTenantId(1L);
            TenantContext.setTenantMode("SAAS");
            UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                    .userId(7L).tenantId(1L).username("review-fixture").build());
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO file_node (id, tenant_id, parent_id, node_type, name, path, "
                    + "file_size, status, upload_status, owner_id, uploader_id, deleted) "
                    + "VALUES (99,1,0,1,'review.txt','/review.txt',2,0,2,7,7,0)");
            var files = context.getBean(FileService.class);
            System.out.println("normal_detail_status=" + files.getNodeDetail(99L).getStatus());
            jdbc.update("UPDATE file_node SET status=1 WHERE id=99");
            files.invalidateAccessible(99L);
            try {
                files.getNodeDetail(99L);
                throw new AssertionError("Expected recycled detail to be denied");
            } catch (BusinessException denied) {
                System.out.println("recycled_detail_business_code=" + denied.getCode());
            } finally {
                UserContext.clear();
                TenantContext.clear();
            }
        }
    }
}
