package com.stcloud.search;

import com.stcloud.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 集成测试：启动完整 Spring 上下文，直接调用 reindexAll 把数据库中的文件节点灌入 ES。
 * 运行后查看日志中 "Reindexed X / Y file nodes to ES" 确认结果。
 */
@SpringBootTest
class ReindexIntegrationTest {

    @Autowired
    private SearchService searchService;

    @Test
    void reindexAll() {
        int count = searchService.reindexAll();
        System.out.println("====== Reindex 完成，成功索引 " + count + " 个节点 ======");
    }
}
