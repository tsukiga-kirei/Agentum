package com.agentum.delivery.application;

/**
 * 邮箱交付连通性测试结果。当前仅反映 TCP 端口是否可达。
 */
public record EmailDeliveryTestOutcome(String status, String summary) {
}
