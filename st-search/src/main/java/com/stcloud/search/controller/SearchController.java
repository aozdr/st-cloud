package com.stcloud.search.controller;

import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.response.Result;
import com.stcloud.search.dto.SearchResultVO;
import com.stcloud.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 全文搜索 API
 */
@Tag(name = "全文搜索", description = "基于 Elasticsearch 的文件内容搜索")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "搜索文件内容", description = "通过关键词搜索文档内容，返回匹配的文件列表及高亮片段")
    @PreAuthorize("hasAuthority('search:file') or hasRole('ADMIN')")
    @GetMapping
    public Result<List<SearchResultVO>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long ownerId = UserContext.getUserId();
        List<SearchResultVO> results = searchService.searchContent(keyword, ownerId, page, size);
        return Result.success(results);
    }

    @Operation(summary = "重建全量索引", description = "将数据库中所有正常状态的文件节点重新索引到 ES（仅管理员）")
    @Auditable(action = "REINDEX", targetType = "SYSTEM", detail = "重建全量索引")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reindex")
    public Result<Integer> reindex() {
        int count = searchService.reindexAll();
        return Result.success(count);
    }
}
