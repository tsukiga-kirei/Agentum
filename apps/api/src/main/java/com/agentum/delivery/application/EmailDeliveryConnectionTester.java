package com.agentum.delivery.application;

/**
 * 邮箱交付连通性测试入口。系统管理页仅做 TCP 端口探测，类似 nc -z。
 */
public interface EmailDeliveryConnectionTester {

    EmailDeliveryTestOutcome test(EmailDeliveryTestRequest request);
}
