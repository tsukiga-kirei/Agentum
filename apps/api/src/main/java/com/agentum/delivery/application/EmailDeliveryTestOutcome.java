package com.agentum.delivery.application;

/**
 * 邮箱交付 SMTP 连通性测试结果。当前只做 connect，不发送测试邮件。
 */
public record EmailDeliveryTestOutcome(String status, String summary) {
}
