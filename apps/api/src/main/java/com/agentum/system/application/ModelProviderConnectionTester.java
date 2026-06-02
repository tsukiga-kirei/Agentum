package com.agentum.system.application;

/**
 * 模型供应商真实连通性测试入口。接口隔离外部 HTTP 细节，便于服务层只关注权限、凭证和审计边界。
 */
public interface ModelProviderConnectionTester {

    ModelProviderTestOutcome test(ModelProviderTestRequest request);
}
