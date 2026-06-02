package com.agentum.delivery.application;

/**
 * 邮箱交付 SMTP 连通性测试入口。系统管理页通过 JavaMail testConnection 验证 SMTP 可达性与认证配置。
 */
public interface EmailDeliveryConnectionTester {

    EmailDeliveryTestOutcome test(EmailDeliveryTestRequest request);
}
