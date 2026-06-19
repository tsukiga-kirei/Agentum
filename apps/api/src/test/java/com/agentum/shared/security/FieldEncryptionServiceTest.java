package com.agentum.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FieldEncryptionServiceTest {

    @Test
    void shouldEncryptFieldWithRandomIvAndDecryptOriginalText() {
        FieldEncryptionService service = new FieldEncryptionService("test-master-key-with-enough-length");

        String firstCipher = service.encrypt("sk-test-secret");
        String secondCipher = service.encrypt("sk-test-secret");

        assertThat(firstCipher).startsWith("v1:");
        assertThat(firstCipher).doesNotContain("sk-test-secret");
        assertThat(secondCipher).isNotEqualTo(firstCipher);
        assertThat(service.decrypt(firstCipher)).isEqualTo("sk-test-secret");
        assertThat(service.decrypt(secondCipher)).isEqualTo("sk-test-secret");
    }

    @Test
    void shouldRejectWeakMasterKey() {
        assertThatThrownBy(() -> new FieldEncryptionService("too-short"))
            .isInstanceOfSatisfying(com.agentum.shared.api.ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("FIELD_ENCRYPTION_KEY_WEAK")
            );
    }
}
