package com.agentum.shared.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 管理台列表统一 Pageable 工厂，集中处理页码边界、size 上限和排序白名单。
 */
public final class PageableFactory {

    private PageableFactory() {
    }

    public static Pageable from(PageQuery query, SortWhitelist sortWhitelist) {
        String[] parts = query.sort().split(",", 2);
        String field = sortWhitelist.resolve(parts[0]);
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;

        return PageRequest.of(query.page() - 1, query.size(), Sort.by(direction, field));
    }
}
