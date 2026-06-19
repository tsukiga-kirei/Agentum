package com.agentum.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 敏感字段加密配置。
 *
 * <p>主密钥必须独立于登录 Token 密钥，并在同一套持久化数据的整个生命周期内保持稳定；
 * 生产环境后续应改由 KMS / Vault 注入和轮换，禁止写入日志或接口响应。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "agentum.security.field-encryption")
public class FieldEncryptionProperties {

    /** 用于派生 AES-GCM 数据密钥的应用主密钥；生产环境必须由密钥管理系统或环境变量注入。 */
    @NotBlank
    @Size(min = 32)
    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
