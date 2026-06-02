# Model Provider Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 系统管理中的模型供应商测试从前端占位改为真实后端接口，并为模型密钥和后续敏感字段提供统一字段加密能力。

**Architecture:** 后端新增通用字段加密服务，模型供应商保存 API Key 时只落密文到 `settings`，接口响应继续只返回配置状态。模型测试接口读取供应商配置、解密密钥，并通过模型供应商连接测试器访问模型列表接口，前端只展示脱敏测试结果。

**Tech Stack:** Java 21、Spring Boot、JPA JSONB、AES-GCM、RestClient、React、TypeScript。

---

### Task 1: 字段加密服务

**Files:**
- Create: `apps/api/src/main/java/com/agentum/shared/security/FieldEncryptionService.java`
- Test: `apps/api/src/test/java/com/agentum/shared/security/FieldEncryptionServiceTest.java`

- [ ] 写单元测试：加密结果不包含明文，同一明文两次密文不同，解密后回到原文。
- [ ] 实现 AES-GCM 字段加密，密钥由服务端配置派生。
- [ ] 运行字段加密测试。

### Task 2: 模型密钥保存和测试接口

**Files:**
- Modify: `apps/api/src/main/java/com/agentum/system/domain/ModelProviderEntity.java`
- Modify: `apps/api/src/main/java/com/agentum/system/application/SystemManagementService.java`
- Modify: `apps/api/src/main/java/com/agentum/system/interfaces/SystemManagementApi.java`
- Modify: `apps/api/src/main/java/com/agentum/system/interfaces/SystemManagementController.java`
- Create: `apps/api/src/main/java/com/agentum/system/application/ModelProviderConnectionTester.java`
- Create: `apps/api/src/main/java/com/agentum/system/application/ModelProviderTestRequest.java`
- Create: `apps/api/src/main/java/com/agentum/system/application/ModelProviderTestOutcome.java`
- Test: `apps/api/src/test/java/com/agentum/system/application/SystemManagementServiceTest.java`

- [ ] 写服务测试：新增供应商时 API Key 加密落库，不回显明文。
- [ ] 写服务测试：未配置密钥时模型测试返回 failed。
- [ ] 写服务测试：配置密钥后测试器收到解密后的密钥。
- [ ] 实现 `POST /api/system/model-providers/{providerId}/test`。
- [ ] 运行系统管理服务测试。

### Task 3: HTTP 连接测试器

**Files:**
- Create: `apps/api/src/main/java/com/agentum/system/infrastructure/HttpModelProviderConnectionTester.java`

- [ ] 实现 OpenAI 兼容 `/models` 测试。
- [ ] 对供应商错误、网络错误和响应解析失败返回中文脱敏摘要。
- [ ] 不记录 API Key、供应商原始敏感响应和完整请求头。

### Task 4: 前端和契约

**Files:**
- Modify: `apps/web/src/types/system.ts`
- Modify: `apps/web/src/services/apiClient.ts`
- Modify: `apps/web/src/surfaces/admin/SystemManagementPage.tsx`
- Modify: `packages/shared-contract/openapi/agentum.openapi.yaml`
- Modify: `docs/progress/README.md`

- [ ] 新增模型测试响应类型和 API client 方法。
- [ ] 模型列表与抽屉按钮调用真实接口。
- [ ] 同步 OpenAPI 与进度文档。
- [ ] 运行前后端验证。
