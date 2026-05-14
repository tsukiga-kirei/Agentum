package com.agentum.shared.pagination;

/**
 * 管理台列表统一分页入参。page 对外从 1 开始，进入 Spring Pageable 前再转换为 0 基页码。
 */
public record PageQuery(int page, int size, String sort) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "updatedAt,desc";

    public PageQuery {
        page = Math.max(page, DEFAULT_PAGE);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
        sort = sort == null || sort.isBlank() ? DEFAULT_SORT : sort.trim();
    }

    public static PageQuery of(int page, int size, String sort) {
        return new PageQuery(page, size, sort);
    }
}
