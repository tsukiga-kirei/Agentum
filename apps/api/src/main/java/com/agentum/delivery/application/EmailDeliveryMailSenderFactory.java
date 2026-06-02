package com.agentum.delivery.application;

import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 统一构建 JavaMail SMTP 客户端，供真实发信与连通性测试复用，避免两处配置漂移。
 */
public final class EmailDeliveryMailSenderFactory {

    private EmailDeliveryMailSenderFactory() {
    }

    public static JavaMailSenderImpl create(EmailDeliverySmtpConfig smtp) {
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
        boolean authEnabled = smtp.username() != null && !smtp.username().isBlank()
            && smtp.password() != null && !smtp.password().isBlank();
        properties.put("mail.smtp.auth", String.valueOf(authEnabled));
        applyTransportSecurity(properties, smtp);
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    static void applyTransportSecurity(Properties properties, EmailDeliverySmtpConfig smtp) {
        switch (smtp.transportMode()) {
            case SMTPS -> {
                // 前端启用 TLS 且端口为 465 时，走 SMTPS 隐式 SSL。
                properties.put("mail.smtp.ssl.enable", "true");
                properties.put("mail.smtp.ssl.trust", smtp.host());
                properties.put("mail.smtp.socketFactory.port", String.valueOf(smtp.port()));
                properties.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                properties.put("mail.smtp.starttls.enable", "false");
            }
            case STARTTLS -> {
                properties.put("mail.smtp.starttls.enable", "true");
                properties.put("mail.smtp.starttls.required", "true");
                properties.put("mail.smtp.ssl.trust", smtp.host());
                properties.put("mail.smtp.ssl.enable", "false");
            }
            case PLAIN -> {
                properties.put("mail.smtp.starttls.enable", "false");
                properties.put("mail.smtp.ssl.enable", "false");
            }
        }
    }
}
