# 开发规范

## 1. 总体原则

Agentum 是长期维护型项目，代码要优先考虑可读性、边界清晰和可测试。

要求：

- 类型明确。
- 命名清晰。
- 注释使用中文。
- 接口格式统一。
- 错误码统一。
- 核心逻辑必须有测试。

## 2. 推荐目录

```text
Agentum/
  docs/
  apps/
    web/
    api/
  packages/
    shared-contract/
    ui/
  workers/
    document-worker/
    ai-worker/
  scripts/
  deploy/
```

如果后端采用 Java / Kotlin，后端可使用标准 Spring Boot 结构：

```text
apps/api/
  src/main/java/
    com/agentum/
      auth/
      workflow/
      agent/
      skill/
      mcp/
      permission/
      audit/
      delivery/
      knowledge/
      modelprovider/
      config/
      shared/
```

当前工程后端采用 Gradle Kotlin DSL 管理多模块：

- 根目录 `build.gradle.kts` 管理公共插件和 Java 版本。
- 根目录 `settings.gradle.kts` 声明 `apps:api` 和 `workers:document-worker`。
- 各模块保留自己的 `build.gradle.kts`。

Gradle Kotlin DSL 中的注释同样使用中文，复杂构建逻辑需要说明原因。

## 3. 命名规范

前端：

- 组件：`PascalCase`
- hooks：`useXxx`
- 工具函数：`camelCase`
- 类型：`PascalCase`
- 常量：`UPPER_SNAKE_CASE`

后端：

- 类名：`PascalCase`
- 方法名：`camelCase`
- 包名：小写
- 数据库表名：`snake_case`
- 数据库字段名：`snake_case`

## 4. 注释规范

注释统一使用中文。

必须写注释：

- 工作流状态机
- 权限判断
- 变量解析
- MCP 调用
- 模型输出解析
- 审计日志
- 重试和补偿逻辑

示例：

```java
// 生产环境只允许执行已发布的工作流版本，避免草稿配置影响正式流程。
if (!workflowVersion.isPublished()) {
    throw new WorkflowPermissionException("当前工作流版本未发布，不能在生产环境执行");
}
```

避免无意义注释：

```java
// 获取名称
String name = workflow.getName();
```

## 5. 接口规范

API 返回格式统一：

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req_xxx"
}
```

错误格式：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "WORKFLOW_NODE_CONFIG_INVALID",
    "message": "智能体节点缺少输出变量配置",
    "details": {}
  },
  "requestId": "req_xxx"
}
```

## 6. 错误码规范

错误码使用大写下划线。

推荐前缀：

- `AUTH_`
- `PERMISSION_`
- `WORKFLOW_`
- `NODE_`
- `AGENT_`
- `SKILL_`
- `MCP_`
- `DELIVERY_`
- `AUDIT_`

错误 message 使用中文。

## 7. 测试要求

必须测试：

- 权限判断
- 工作流发布校验
- 条件分支
- 变量解析
- 节点状态流转
- 智能体输出 schema 校验
- MCP 权限控制
- 交付节点失败重试

## 8. 格式化要求

前端：

- ESLint
- Prettier
- TypeScript strict mode

后端：

- Checkstyle / Spotless
- 单元测试
- 集成测试
- Gradle Kotlin DSL 配置保持类型明确，避免把复杂业务逻辑塞进构建脚本。

文档：

- Markdown 标题层级清晰。
- 同类表格字段统一。
- 文档内的业务术语保持一致。
