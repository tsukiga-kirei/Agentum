package com.agentum.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.delivery.application.EmailDeliveryTestRequest;
import com.agentum.shared.security.FieldEncryptionService;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JavaMailEmailDeliveryConnectionTesterTest {

    private static final FieldEncryptionService FIELD_ENCRYPTION = new FieldEncryptionService("test-master-key-with-enough-length");

    @Test
    void shouldReturnSuccessWhenTcpPortIsOpen() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            JavaMailEmailDeliveryConnectionTester tester = new JavaMailEmailDeliveryConnectionTester(FIELD_ENCRYPTION);

            var outcome = tester.test(new EmailDeliveryTestRequest(UUID.randomUUID(), Map.of(
                "sourceType", "builtin",
                "deliveryChannel", "email",
                "smtpHost", "127.0.0.1",
                "smtpPort", port,
                "fromAddress", "agentum@example.test",
                "useTls", false
            )));

            assertThat(outcome.status()).isEqualTo("success");
            assertThat(outcome.summary())
                .contains("TCP 连接成功")
                .contains("127.0.0.1:" + port)
                .contains("仅验证端口可达");
        }
    }

    @Test
    void shouldFailWhenTcpPortIsClosed() {
        JavaMailEmailDeliveryConnectionTester tester = new JavaMailEmailDeliveryConnectionTester(FIELD_ENCRYPTION) {
            @Override
            protected void probeTcp(String host, int port, int timeoutMs) throws IOException {
                throw new IOException("Connection refused");
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
        assertThat(outcome.summary()).contains("TCP 连接失败");
    }
}
