package com.agentum.shared.pagination;

import java.util.List;
import org.springframework.data.domain.Page;

// 管理台列表统一返回分页信息，避免前端默认一次性加载全量治理数据。
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long total,
    int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber() + 1,
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
