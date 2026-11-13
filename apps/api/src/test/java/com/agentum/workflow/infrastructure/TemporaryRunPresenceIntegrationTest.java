package com.agentum.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.workflow.domain.WorkflowRunEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 只使用显式提供的隔离 PostgreSQL，验证迁移、心跳批量更新和旧实体保存之间的边界。 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.locations=classpath:db/migration/schema"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AGENTUM_TEST_DATABASE_URL", matches = ".+")
class TemporaryRunPresenceIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AGENTUM_TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "temporary_test_only");
    }

    @Autowired WorkflowRunRepository runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;

    @Test
    void heartbeatMustSurviveSavingAnOlderEntityAndSavedTasksMustNotExpire() {
        UUID tenantId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant start = Instant.parse("2026-11-13T01:00:00Z");
        // 只创建迁移要求的最小外键事实，不使用或读取任何开发/业务数据。
        jdbc.update("insert into tenants(id, name, code, status) values (?, '测试租户', ?, 'active')", tenantId, tenantId.toString());
        jdbc.update("insert into workflow_definitions(id, tenant_id, name, description, status) values (?, ?, '测试流程', '', 'draft')", workflowId, tenantId);
        jdbc.update("insert into workflow_versions(id, workflow_id, tenant_id, version_number, definition_snapshot, node_count) values (?, ?, ?, 1, '{}', 1)", versionId, workflowId, tenantId);
        WorkflowRunEntity run = WorkflowRunEntity.create(tenantId, workflowId, versionId, 1, "测试", "测试", null, 1, "TEMP-1", start);
        runs.saveAndFlush(run);
        Instant heartbeat = start.plusSeconds(90);
        assertThat(runs.touchTemporaryRun(run.getId(), heartbeat)).isEqualTo(1);
        // 当前实体仍持有旧心跳；业务状态写入不得覆盖刚写入数据库的新心跳。
        run.pauseAt("input", "填写", "user_input", 0, heartbeat);
        runs.saveAndFlush(run);
        entities.clear();
        WorkflowRunEntity fresh = runs.lockById(run.getId()).orElseThrow();
        assertThat(fresh.getTemporaryLastSeenAt()).isEqualTo(heartbeat);
        assertThat(runs.findTop100BySavedFalseAndTemporaryLastSeenAtBeforeOrderByTemporaryLastSeenAtAsc(start.plusSeconds(60))).isEmpty();
        fresh.markSaved(heartbeat);
        runs.saveAndFlush(fresh);
        assertThat(runs.touchTemporaryRun(run.getId(), heartbeat.plusSeconds(10))).isZero();
        assertThat(runs.findTop100BySavedFalseAndTemporaryLastSeenAtBeforeOrderByTemporaryLastSeenAtAsc(heartbeat.plusSeconds(1000))).isEmpty();
    }
}
