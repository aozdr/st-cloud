package com.stcloud.core.service.impl;

import com.stcloud.core.CoreTestApplication;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

/** 显式独立本机 MySQL 库执行同一矩阵，每条测试事务自动回滚，禁止默认访问共享库。 */
@EnabledIfSystemProperty(named = "test.team.recycle.mysql.url", matches = ".+stcloud_team_recycle_20260930.*")
@SpringBootTest(classes = CoreTestApplication.class, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${test.team.recycle.mysql.url}",
        "spring.datasource.username=${test.team.recycle.mysql.user:root}",
        "spring.datasource.password=${test.team.recycle.mysql.password}",
        "spring.sql.init.mode=never"
})
class TeamRecycleBinMysqlIntegrationTest extends TeamRecycleBinIntegrationTest {
}
