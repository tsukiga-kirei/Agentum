package com.agentum.delivery.template;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 每个发布版本独立保存原件；不复用运行附件的过期清理策略。 */
@Entity
@Table(name = "delivery_template_versions")
public class DeliveryTemplateEntity {
    @Id UUID id;
    @Column(name = "template_id", nullable = false) UUID templateId;
    @Column(name = "tenant_id", nullable = false) UUID tenantId;
    @Column(nullable = false) String name;
    @Column(name = "file_name", nullable = false) String fileName;
    @Column(nullable = false) String kind;
    @Column(nullable = false) int version;
    @Column(nullable = false) String status;
    @Column(name = "read_scope", nullable = false) String readScope;
    @Column(name = "created_by", nullable = false) UUID createdBy;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") Map<String, Object> spec;
    @JdbcTypeCode(SqlTypes.VARBINARY) @Column(name = "file_bytes", nullable = false, columnDefinition = "bytea") byte[] fileBytes;
    @Column(name = "deleted_at") Instant deletedAt;
    @Version @Column(name = "lock_version", nullable = false) long lockVersion;
    protected DeliveryTemplateEntity() {}
}
