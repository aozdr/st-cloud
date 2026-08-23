package com.stcloud.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索结果分页对象：携带总命中数与当前页记录，供前端分类页/搜索页正确分页。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultPage {
    private List<SearchResultVO> records;
    private long total;
    private int page;
    private int size;
}
