package com.stcloud.team.service;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import com.stcloud.team.TeamTestApplication;

/** 使用显式指定的独立 MySQL 测试库；不允许默认连接共享开发库。 */
@EnabledIfSystemProperty(named = "test.review.mysql.url", matches = ".+stcloud_review_fixes_20260930.*")
@SpringBootTest(classes = TeamTestApplication.class, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${test.review.mysql.url}",
        "spring.datasource.username=${test.review.mysql.user:root}",
        "spring.datasource.password=${test.review.mysql.password}",
        "spring.sql.init.mode=never"
})
class TeamRoleMysqlConcurrencyIntegrationTest extends TeamRoleConcurrencyIntegrationTest {
    // 继承两种锁顺序下的成员/邀请/接受邀请矩阵以及提交/回滚权限隔离场景。
}
