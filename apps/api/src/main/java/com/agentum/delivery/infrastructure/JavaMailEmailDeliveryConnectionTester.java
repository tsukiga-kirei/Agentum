package com.agentum.delivery.infrastructure;

import com.agentum.delivery.application.EmailDeliveryConnectionTester;
import com.agentum.delivery.application.EmailDeliveryMailSenderFactory;
import com.agentum.delivery.application.EmailDeliverySmtpConfig;
import com.agentum.delivery.application.EmailDeliveryTestOutcome;
import com.agentum.delivery.application.EmailDeliveryTestRequest;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class JavaMailEmailDeliveryConnectionTester implements EmailDeliveryConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(JavaMailEmailDeliveryConnectionTester.class);

    private final FieldEncryptionService fieldEncryptionService;

    public JavaMailEmailDeliveryConnectionTester(FieldEncryptionService fieldEncryptionService) {
        this.fieldEncryptionService = fieldEncryptionService;
    }

    @Override
    public EmailDeliveryTestOutcome test(EmailDeliveryTestRequest request) {
        try {
            EmailDeliverySmtpConfig smtp = EmailDeliverySmtpConfig.fromCapabilityConfig(request.config(), fieldEncryptionService);
            JavaMailSenderImpl sender = createMailSender(smtp);
            // 只建立 SMTP 连接并验证认证，不发送测试邮件，避免污染 Mailpit 或触发生产限流。
            sender.testConnection();
            return new EmailDeliveryTestOutcome("success", buildSuccessSummary(smtp));
        } catch (ApiException ex) {
            return new EmailDeliveryTestOutcome("failed", ex.getMessage());
        } catch (MessagingException ex) {
            log.warn(
                "邮箱交付 SMTP 连接测试失败 capabilityId={} host={} port={} transport={} errorType={} message={}",
                request.capabilityId(),
                smtpSafeHost(request),
                smtpSafePort(request),
                smtpSafeTransport(request),
                ex.getClass().getSimpleName(),
                ex.getMessage()
            );
            return new EmailDeliveryTestOutcome("failed", buildFailureSummary(request, ex));
        } catch (Exception ex) {
            log.warn(
                "邮箱交付 SMTP 连接测试异常 capabilityId={} host={} port={} errorType={} message={}",
                request.capabilityId(),
                smtpSafeHost(request),
                smtpSafePort(request),
                ex.getClass().getSimpleName(),
                ex.getMessage()
            );
            return new EmailDeliveryTestOutcome("failed", buildFailureSummary(request, ex));
        }
    }

    private String buildFailureSummary(EmailDeliveryTestRequest request, Exception ex) {
        try {
            EmailDeliverySmtpConfig smtp = EmailDeliverySmtpConfig.fromCapabilityConfig(request.config(), fieldEncryptionService);
            return "SMTP 连接失败（" + smtp.host() + ":" + smtp.port() + "，" + describeTransport(smtp) + "）：" + ex.getMessage();
        } catch (Exception ignored) {
            return "SMTP 连接失败：" + ex.getMessage();
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

    private static String smtpSafeTransport(EmailDeliveryTestRequest request) {
        try {
            Object portValue = request.config().get("smtpPort");
            int port = portValue == null ? -1 : Integer.parseInt(portValue.toString());
            Object useTls = request.config().get("useTls");
            boolean tls = useTls instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(useTls));
            if (!tls) {
                return "PLAIN";
            }
            return port == 465 ? "SMTPS" : "STARTTLS";
        } catch (Exception ex) {
            return "-";
        }
    }

    protected JavaMailSenderImpl createMailSender(EmailDeliverySmtpConfig smtp) {
        return EmailDeliveryMailSenderFactory.create(smtp);
    }

    private static String buildSuccessSummary(EmailDeliverySmtpConfig smtp) {
        boolean authEnabled = smtp.username() != null && !smtp.username().isBlank()
            && smtp.password() != null && !smtp.password().isBlank();
        String auth = authEnabled ? "已启用 SMTP 认证" : "未启用 SMTP 认证";
        return "邮箱交付连接成功：" + smtp.host() + ":" + smtp.port()
            + "（" + describeTransport(smtp) + "，" + auth + "），发件地址 " + smtp.fromAddress();
    }

    private static String describeTransport(EmailDeliverySmtpConfig smtp) {
        return switch (smtp.transportMode()) {
            case SMTPS -> "SMTPS 隐式 SSL";
            case STARTTLS -> "STARTTLS";
            case PLAIN -> "明文";
        };
    }
}
