package com.agentum.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class PageableFactoryTest {

    @Test
    void normalizesPageAndSize() {
        PageQuery query = PageQuery.of(0, 500, "name,asc");

        Pageable pageable = PageableFactory.from(query, SortWhitelist.of("updatedAt", "name", "updatedAt"));

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(pageable.getSort().getOrderFor("name")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("name").isAscending()).isTrue();
    }

    @Test
    void fallsBackToDefaultSortWhenFieldIsNotWhitelisted() {
        PageQuery query = PageQuery.of(2, 20, "rawSql,asc");

        Pageable pageable = PageableFactory.from(query, SortWhitelist.of("updatedAt", "name", "updatedAt"));

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getSort().getOrderFor("updatedAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("updatedAt").isAscending()).isTrue();
    }
}
