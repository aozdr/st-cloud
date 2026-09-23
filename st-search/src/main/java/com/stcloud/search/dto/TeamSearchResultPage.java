package com.stcloud.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 团队搜索结果页。
 *
 * <p>团队结果不暴露 ES total；游标只在确实存在下一条可见记录时返回。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamSearchResultPage {

    private List<SearchResultVO> records;
    private boolean hasMore;
    private String nextCursor;
}
