package com.agentum.delivery.application;

/**
 * SMTP 传输安全模式。465 使用隐式 SSL（SMTPS），587 等端口使用 STARTTLS 或明文。
 */
public enum EmailDeliveryTransportMode {
    /** 465 等 SMTPS 端口，连接建立时即走 SSL */
    SMTPS,
    /** 587/25 等端口的 STARTTLS 升级 */
    STARTTLS,
    /** 本地 Mailpit 等明文 SMTP */
    PLAIN
}
