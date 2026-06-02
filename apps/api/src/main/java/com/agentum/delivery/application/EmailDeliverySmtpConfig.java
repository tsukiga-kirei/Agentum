package com.agentum.delivery.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import java.util.Map;
import org.springframework.http.HttpStatus;

public record EmailDeliverySmtpConfig(
    String host,
    int port,
    String username,
    String password,
    String fromAddress,
    boolean useTls
) {

    public static EmailDeliverySmtpConfig fromCapabilityConfig(Map<String, Object> config, FieldEncryptionService fieldEncryptionService) {
        String sourceType = stringValue(config.get("sourceType"));
        if (sourceType != null && !"builtin".equals(sourceType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CHANNEL_INVALID", "当前交付能力不是系统内置邮箱通道");
        }
        if (!"email".equals(stringValue(config.get("deliveryChannel")))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CHANNEL_INVALID", "当前交付能力不是邮箱通道");
        }
        String encryptedPassword = stringValue(config.get("encryptedSmtpPassword"));
        return new EmailDeliverySmtpConfig(
            require(config, "smtpHost", "SMTP 主机不能为空"),
            parsePort(config.get("smtpPort")),
            stringValue(config.get("smtpUsername")),
            encryptedPassword == null ? null : fieldEncryptionService.decrypt(encryptedPassword),
            require(config, "fromAddress", "发件邮箱不能为空"),
            booleanValue(config.get("useTls"))
        );
    }

    private static String require(Map<String, Object> config, String key, String message) {
        String value = stringValue(config.get(key));
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_CONFIG_REQUIRED", message);
        }
        return value;
    }

    private static int parsePort(Object value) {
        String text = stringValue(value);
        if (text == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_PORT_REQUIRED", "SMTP 端口不能为空");
        }
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EMAIL_PORT_INVALID", "SMTP 端口必须是 1 到 65535 之间的数字");
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = stringValue(value);
        return text != null && ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
