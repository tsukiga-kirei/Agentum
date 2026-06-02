package com.agentum.delivery.infrastructure;

import com.agentum.delivery.application.EmailDeliveryConnectionTester;
import com.agentum.delivery.application.EmailDeliverySmtpConfig;
import com.agentum.delivery.application.EmailDeliveryTestOutcome;
import com.agentum.delivery.application.EmailDeliveryTestRequest;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JavaMailEmailDeliveryConnectionTester implements EmailDeliveryConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(JavaMailEmailDeliveryConnectionTester.class);
    private static final int TCP_CONNECT_TIMEOUT_MS = 5000;

    private final FieldEncryptionService fieldEncryptionService;

    public JavaMailEmailDeliveryConnectionTester(FieldEncryptionService fieldEncryptionService) {
        this.fieldEncryptionService = fieldEncryptionService;
    }

    @Override
    public EmailDeliveryTestOutcome test(EmailDeliveryTestRequest request) {
        try {
            EmailDeliverySmtpConfig smtp = EmailDeliverySmtpConfig.fromCapabilityConfig(request.config(), fieldEncryptionService);
            // 系统管理页只做 TCP 端口探测，类似 nc -z；不执行 TLS 握手、SMTP 协议或 AUTH，避免证书与协议差异导致误报。
            probeTcp(smtp.host(), smtp.port(), TCP_CONNECT_TIMEOUT_MS);
            return new EmailDeliveryTestOutcome("success", buildSuccessSummary(smtp));
        } catch (ApiException ex) {
            return new EmailDeliveryTestOutcome("failed", ex.getMessage());
        } catch (IOException ex) {
            log.warn(
                "邮箱交付 TCP 连接测试失败 capabilityId={} host={} port={} errorType={} message={}",
                request.capabilityId(),
                smtpSafeHost(request),
                smtpSafePort(request),
                ex.getClass().getSimpleName(),
                ex.getMessage()
            );
            return new EmailDeliveryTestOutcome("failed", buildFailureSummary(request, ex));
        } catch (Exception ex) {
            log.warn(
                "邮箱交付 TCP 连接测试异常 capabilityId={} host={} port={} errorType={} message={}",
                request.capabilityId(),
                smtpSafeHost(request),
                smtpSafePort(request),
                ex.getClass().getSimpleName(),
                ex.getMessage()
            );
            return new EmailDeliveryTestOutcome("failed", buildFailureSummary(request, ex));
        }
    }

    protected void probeTcp(String host, int port, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
        }
    }

    private String buildFailureSummary(EmailDeliveryTestRequest request, Exception ex) {
        try {
            EmailDeliverySmtpConfig smtp = EmailDeliverySmtpConfig.fromCapabilityConfig(request.config(), fieldEncryptionService);
            return "TCP 连接失败（" + smtp.host() + ":" + smtp.port() + "）：" + ex.getMessage()
                + "；本测试仅验证端口可达，不校验 TLS 证书与 SMTP 认证";
        } catch (Exception ignored) {
            return "TCP 连接失败：" + ex.getMessage();
        }
    }

    private static String smtpSafeHost(EmailDeliveryTestRequest request) {
        Object value = request.config().get("smtpHost");
        return value == null ? "-" : value.toString();
    }

    private static String smtpSafePort(EmailDeliveryTestRequest request) {
        Object value = request.config().get("smtpPort");
        return value == null ? "-" : value.toString();
    }

    private static String buildSuccessSummary(EmailDeliverySmtpConfig smtp) {
        return "邮箱交付 TCP 连接成功：" + smtp.host() + ":" + smtp.port()
            + "（仅验证端口可达，未校验 TLS 证书与 SMTP 认证），发件地址 " + smtp.fromAddress();
    }
}
