package com.agentum.attachment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "attachment_recognition_settings")
public class AttachmentRecognitionSettingEntity {

    @Id
    private Short id;

    @Column(name = "recognition_enabled", nullable = false)
    private boolean recognitionEnabled;

    @Column(name = "recognition_engine", nullable = false, length = 20)
    private String recognitionEngine;

    @Column(name = "max_file_size_mb", nullable = false)
    private int maxFileSizeMb;

    @Column(name = "max_files_per_field", nullable = false)
    private int maxFilesPerField;

    @Column(name = "max_extracted_chars", nullable = false)
    private int maxExtractedChars;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "retention_policy", nullable = false, length = 20)
    private String retentionPolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mineru_supported_extensions", nullable = false, columnDefinition = "jsonb")
    private List<String> mineruSupportedExtensions;

    @Column(name = "mineru_endpoint", length = 800)
    private String mineruEndpoint;

    @Column(name = "encrypted_mineru_api_key")
    private String encryptedMineruApiKey;

    @Column(name = "mineru_backend", nullable = false, length = 80)
    private String mineruBackend;

    @Column(name = "mineru_parse_method", nullable = false, length = 20)
    private String mineruParseMethod;

    @Column(name = "mineru_language", nullable = false, length = 40)
    private String mineruLanguage;

    @Column(name = "mineru_enable_formula", nullable = false)
    private boolean mineruEnableFormula;

    @Column(name = "mineru_enable_table", nullable = false)
    private boolean mineruEnableTable;

    @Column(name = "mineru_connect_timeout_seconds", nullable = false)
    private int mineruConnectTimeoutSeconds;

    @Column(name = "mineru_read_timeout_seconds", nullable = false)
    private int mineruReadTimeoutSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AttachmentRecognitionSettingEntity() {
    }

    public void update(
        boolean recognitionEnabled,
        String recognitionEngine,
        int maxFileSizeMb,
        int maxFilesPerField,
        int maxExtractedChars,
        String retentionPolicy,
        int retentionDays,
        List<String> mineruSupportedExtensions,
        String mineruEndpoint,
        String encryptedMineruApiKey,
        String mineruBackend,
        String mineruParseMethod,
        String mineruLanguage,
        boolean mineruEnableFormula,
        boolean mineruEnableTable,
        int mineruConnectTimeoutSeconds,
        int mineruReadTimeoutSeconds,
        Instant now
    ) {
        this.recognitionEnabled = recognitionEnabled;
        this.recognitionEngine = recognitionEngine;
        this.maxFileSizeMb = maxFileSizeMb;
        this.maxFilesPerField = maxFilesPerField;
        this.maxExtractedChars = maxExtractedChars;
        this.retentionPolicy = retentionPolicy;
        this.retentionDays = retentionDays;
        this.mineruSupportedExtensions = new ArrayList<>(mineruSupportedExtensions);
        this.mineruEndpoint = mineruEndpoint;
        this.encryptedMineruApiKey = encryptedMineruApiKey;
        this.mineruBackend = mineruBackend;
        this.mineruParseMethod = mineruParseMethod;
        this.mineruLanguage = mineruLanguage;
        this.mineruEnableFormula = mineruEnableFormula;
        this.mineruEnableTable = mineruEnableTable;
        this.mineruConnectTimeoutSeconds = mineruConnectTimeoutSeconds;
        this.mineruReadTimeoutSeconds = mineruReadTimeoutSeconds;
        this.updatedAt = now;
    }

    public boolean isRecognitionEnabled() { return recognitionEnabled; }
    public String getRecognitionEngine() { return recognitionEngine; }
    public int getMaxFileSizeMb() { return maxFileSizeMb; }
    public int getMaxFilesPerField() { return maxFilesPerField; }
    public int getMaxExtractedChars() { return maxExtractedChars; }
    public int getRetentionDays() { return retentionDays; }
    public String getRetentionPolicy() { return retentionPolicy; }
    public List<String> getMineruSupportedExtensions() { return mineruSupportedExtensions == null ? List.of() : List.copyOf(mineruSupportedExtensions); }
    public String getMineruEndpoint() { return mineruEndpoint; }
    public String getEncryptedMineruApiKey() { return encryptedMineruApiKey; }
    public String getMineruBackend() { return mineruBackend; }
    public String getMineruParseMethod() { return mineruParseMethod; }
    public String getMineruLanguage() { return mineruLanguage; }
    public boolean isMineruEnableFormula() { return mineruEnableFormula; }
    public boolean isMineruEnableTable() { return mineruEnableTable; }
    public int getMineruConnectTimeoutSeconds() { return mineruConnectTimeoutSeconds; }
    public int getMineruReadTimeoutSeconds() { return mineruReadTimeoutSeconds; }
    public Instant getUpdatedAt() { return updatedAt; }
}
