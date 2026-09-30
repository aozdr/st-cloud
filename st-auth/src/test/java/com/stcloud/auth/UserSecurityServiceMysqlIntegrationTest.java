package com.stcloud.auth;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

/** 仅通过显式参数连接已创建的本地独立测试库，逐条事务回滚。 */
@EnabledIfSystemProperty(named = "test.tenant.context.mysql.url", matches = ".+stcloud_team_recycle_20260930.*")
@SpringBootTest(classes = AuthTestApplication.class, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${test.tenant.context.mysql.url}",
        "spring.datasource.username=${test.tenant.context.mysql.user:root}",
        "spring.datasource.password=${test.tenant.context.mysql.password}",
        "stcloud.jwt.master-key=${test.tenant.context.mysql.master-key:stcloud-dev-master-key-change-in-production}",
        "spring.sql.init.mode=never"
})
class UserSecurityServiceMysqlIntegrationTest extends UserSecurityServiceIntegrationTest {
}
