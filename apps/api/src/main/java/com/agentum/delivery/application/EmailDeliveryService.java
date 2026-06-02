package com.agentum.delivery.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.system.domain.SystemCapabilityEntity;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

    private final FieldEncryptionService fieldEncryptionService;

    public EmailDeliveryService(FieldEncryptionService fieldEncryptionService) {
        this.fieldEncryptionService = fieldEncryptionService;
    }

    public void send(SystemCapabilityEntity capability, EmailDeliveryMessage message) {
        EmailDeliverySmtpConfig smtp = EmailDeliverySmtpConfig.fromCapabilityConfig(capability.getConfig(), fieldEncryptionService);
        validateMessage(message);
        JavaMailSenderImpl sender = buildSender(smtp);
        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(smtp.fromAddress());
            helper.setTo(message.to().toArray(String[]::new));
            if (message.cc() != null && !message.cc().isEmpty()) {
                helper.setCc(message.cc().toArray(String[]::new));
            }
            if (message.bcc() != null && !message.bcc().isEmpty()) {
                helper.setBcc(message.bcc().toArray(String[]::new));
            }
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);
            attachFiles(helper, message.attachmentPaths());
            sender.send(mimeMessage);
            log.info("邮箱交付发送成功 capabilityId={} toCount={} attachmentCount={} requestId={}",
                capability.getId(),
                message.to().size(),
                message.attachmentPaths() == null ? 0 : message.attachmentPaths().size(),
                RequestIds.current()
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (MessagingException ex) {
            log.warn("邮箱交付组装失败 capabilityId={} requestId={}", capability.getId(), RequestIds.current());
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_MESSAGE_INVALID", "邮件内容或附件无法组装");
        } catch (Exception ex) {
            log.warn("邮箱交付发送失败 capabilityId={} host={} port={} requestId={}", capability.getId(), smtp.host(), smtp.port(), RequestIds.current());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_EMAIL_SEND_FAILED", "邮件发送失败，请检查 SMTP 配置或网络连通性");
        }
    }

    private static JavaMailSenderImpl buildSender(EmailDeliverySmtpConfig smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        if (smtp.username() != null && !smtp.username().isBlank()) {
            sender.setUsername(smtp.username());
        }
        if (smtp.password() != null && !smtp.password().isBlank()) {
            sender.setPassword(smtp.password());
        }
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", String.valueOf(smtp.username() != null && !smtp.username().isBlank() && smtp.password() != null && !smtp.password().isBlank()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(smtp.useTls()));
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    private static void attachFiles(MimeMessageHelper helper, List<Path> attachmentPaths) throws MessagingException {
        if (attachmentPaths == null) {
            return;
        }
        for (Path attachmentPath : attachmentPaths) {
            if (attachmentPath == null || !Files.isRegularFile(attachmentPath)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_ATTACHMENT_NOT_FOUND", "邮件附件不存在");
            }
            helper.addAttachment(attachmentPath.getFileName().toString(), attachmentPath.toFile());
        }
    }

    private static void validateMessage(EmailDeliveryMessage message) {
        if (message == null || message.to() == null || message.to().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_RECIPIENT_REQUIRED", "收件邮箱不能为空");
        }
        if (message.subject() == null || message.subject().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_SUBJECT_REQUIRED", "邮件主题不能为空");
        }
        if (message.body() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_BODY_REQUIRED", "邮件正文不能为空");
        }
    }
}
