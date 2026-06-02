package com.agentum.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.agentum.delivery.application.EmailDeliveryTestRequest;
import com.agentum.shared.security.FieldEncryptionService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class JavaMailEmailDeliveryConnectionTesterTest {

    private static final FieldEncryptionService FIELD_ENCRYPTION = new FieldEncryptionService("test-master-key-with-enough-length");

    @Test
    void shouldReturnSuccessSummaryWhenSmtpConnectSucceeds() throws Exception {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        doNothing().when(sender).testConnection();

        JavaMailEmailDeliveryConnectionTester tester = new JavaMailEmailDeliveryConnectionTester(FIELD_ENCRYPTION) {
            @Override
            protected JavaMailSenderImpl createMailSender(com.agentum.delivery.application.EmailDeliverySmtpConfig smtp) {
                return sender;
            }
        };

        var outcome = tester.test(new EmailDeliveryTestRequest(UUID.randomUUID(), Map.of(
            "sourceType", "builtin",
            "deliveryChannel", "email",
            "smtpHost", "localhost",
            "smtpPort", 1025,
            "fromAddress", "agentum@example.test",
            "useTls", false
        )));

        assertThat(outcome.status()).isEqualTo("success");
        assertThat(outcome.summary())
            .contains("邮箱交付连接成功")
            .contains("localhost:1025")
            .contains("agentum@example.test");
    }

    @Test
    void shouldFailWhenSmtpConnectThrows() throws Exception {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        org.mockito.Mockito.doThrow(new jakarta.mail.MessagingException("Connection refused"))
            .when(sender)
            .testConnection();

        JavaMailEmailDeliveryConnectionTester tester = new JavaMailEmailDeliveryConnectionTester(FIELD_ENCRYPTION) {
            @Override
            protected JavaMailSenderImpl createMailSender(com.agentum.delivery.application.EmailDeliverySmtpConfig smtp) {
                return sender;
            }
        };

        var outcome = tester.test(new EmailDeliveryTestRequest(UUID.randomUUID(), Map.of(
            "sourceType", "builtin",
            "deliveryChannel", "email",
            "smtpHost", "localhost",
            "smtpPort", 1025,
            "fromAddress", "agentum@example.test",
            "useTls", false
        )));

        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.summary()).contains("SMTP 连接失败");
    }
}
