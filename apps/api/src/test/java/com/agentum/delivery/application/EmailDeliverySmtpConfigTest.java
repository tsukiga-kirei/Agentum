package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.shared.security.FieldEncryptionService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailDeliverySmtpConfigTest {

    @Test
    void shouldParseSmtpConfigAndDecryptPassword() {
        FieldEncryptionService encryption = new FieldEncryptionService("test-master-key-with-enough-length");

        EmailDeliverySmtpConfig config = EmailDeliverySmtpConfig.fromCapabilityConfig(Map.of(
            "deliveryChannel", "email",
            "smtpHost", "localhost",
            "smtpPort", 1025,
            "smtpUsername", "mailpit-user",
            "encryptedSmtpPassword", encryption.encrypt("smtp-secret"),
            "fromAddress", "agentum@example.test",
            "useTls", "true"
        ), encryption);

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(1025);
        assertThat(config.username()).isEqualTo("mailpit-user");
        assertThat(config.password()).isEqualTo("smtp-secret");
        assertThat(config.fromAddress()).isEqualTo("agentum@example.test");
        assertThat(config.useTls()).isTrue();
    }
}
