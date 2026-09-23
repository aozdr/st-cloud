package com.stcloud.search.service;

import com.stcloud.search.dto.TeamSearchResultPage;

import java.util.List;

/** 团队范围全文搜索服务。 */
public interface TeamSearchService {

    TeamSearchResultPage search(Long tenantId, Long userId, Long spaceId, Long folderId,
                                String keyword, int size, String cursor, Integer nodeType,
                                List<String> suffixes, Long sizeMin, Long sizeMax,
                                Long dateFrom, Long dateTo);
}
