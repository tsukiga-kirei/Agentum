package com.agentum.shared.security;

import com.agentum.shared.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 通用字段加密服务，用于模型密钥、外部系统凭证等敏感字段落库前加密。
 * 当前实现采用应用级主密钥派生 AES-GCM 密钥，后续可平滑替换为 KMS / Vault 托管密钥。
 */
@Component
public class FieldEncryptionService {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public FieldEncryptionService(FieldEncryptionProperties properties) {
        this(properties.getMasterKey());
    }

    /** 仅供不启动 Spring 容器的单元测试显式提供测试密钥。 */
    public FieldEncryptionService(String masterKey) {
        this.keySpec = new SecretKeySpec(deriveKey(masterKey), "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(cipherText);
        } catch (GeneralSecurityException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_ENCRYPTION_FAILED", "敏感字段加密失败");
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (!cipherText.startsWith(PREFIX)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_ENCRYPTION_VERSION_UNSUPPORTED", "敏感字段密文版本不受支持");
        }
        String[] parts = cipherText.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_ENCRYPTION_FORMAT_INVALID", "敏感字段密文格式不正确");
        }
        try {
            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_DECRYPTION_FAILED", "敏感字段解密失败");
        }
    }

    private static byte[] deriveKey(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_ENCRYPTION_KEY_MISSING", "字段加密主密钥未配置");
        }
        if (masterKey.length() < 32) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_ENCRYPTION_KEY_WEAK", "字段加密主密钥长度不能少于 32 个字符");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("AgentumFieldEncryption".getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest.digest(masterKey.getBytes(StandardCharsets.UTF_8)), 32);
        } catch (GeneralSecurityException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FIELD_ENCRYPTION_KEY_DERIVE_FAILED", "字段加密密钥派生失败");
        }
    }
}
