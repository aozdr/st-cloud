package com.stcloud.common.cache;

/**
 * 可重建派生数据缓存抽象（TASK-003）。
 * <p>
 * 用于权限 / 可访问性等短期缓存；提供 get / put / 前缀批量失效 / 清空 / 计数。
 * 默认实现为进程内 {@link TtlCache}；多实例部署时可配置切换 {@link RedisTtlCache}，
 * 以消除跨实例 TTL 兜底窗口。key 与失效语义由调用方约定，各实现保持一致。
 */
public interface Cache {

    /** 读取缓存；不存在或已过期返回 null */
    Object get(String key);

    /** 写入缓存，TTL 由实现决定（构造时指定） */
    void put(String key, Object value);

    /** 按 key 前缀批量失效 */
    void removeByPrefix(String prefix);

    /** 清空全部缓存 */
    void clear();

    /** 当前条目数 */
    int size();
}
