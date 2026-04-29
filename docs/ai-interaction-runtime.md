# AI 交互运行设计

## 1. 设计目标

Agentum 的 AI 交互不是简单调用一次大模型，而是围绕“智能体节点”完成一次可配置、可审计、可暂停、可复用的执行过程。

一次智能体执行需要同时处理：

- 系统提示词
- 工作流变量
- 用户输入
- Skills
- MCP 工具
- 知识库 / RAG
- 模型配置
- 输出 Schema
- 追问或一次性输出模式
- 权限与审计

核心目标：

- 让智能体能力可以装配。
- 让工具调用可控。
- 让知识检索可追踪。
- 让输出结果结构化。
- 让用户追问、确认、补充输入可以暂停和恢复流程。

## 2. 核心模块

AI 交互建议由以下模块协作完成：

```text
Workflow Executor
  |
  v
Agent Runtime
  |
  |-- Prompt Composer
  |-- Skill Resolver
  |-- Knowledge Retriever
  |-- MCP Tool Gateway
  |-- Model Provider Gateway
  |-- Output Parser
  |-- Interaction Controller
  |-- Audit Recorder
```

### 2.1 Workflow Executor

工作流执行器负责决定什么时候执行智能体节点。

职责：

- 准备节点输入变量。
- 创建 `NodeRun`。
- 调用 Agent Runtime。
- 根据返回结果决定继续、暂停、失败或重试。
- 将输出变量写入变量快照。

### 2.2 Agent Runtime

智能体运行时负责一次智能体节点的完整 AI 执行。

职责：

- 读取智能体模板版本。
- 解析节点配置。
- 装配 Skills。
- 装配 MCP 工具。
- 检索知识库。
- 组装最终 Prompt。
- 调用模型。
- 处理模型工具调用。
- 校验结构化输出。
- 生成用户追问或最终结果。
- 写入审计日志。

### 2.3 MCP Tool Gateway

MCP 工具网关负责所有外部工具调用。

职责：

- MCP 服务注册。
- 工具列表同步。
- 参数 Schema 校验。
- 权限校验。
- 凭证注入。
- 调用限流。
- 结果脱敏。
- 调用审计。

智能体不能绕过 MCP Tool Gateway 直接调用外部系统。

## 3. 智能体节点配置

一个智能体节点至少包含：

```json
{
  "agentTemplateId": "agt_requirement_analysis",
  "agentTemplateVersion": 3,
  "inputVariables": ["project_info", "requirement_doc"],
  "outputVariables": ["req_list", "risk_level"],
  "skills": [
    { "skillId": "skill_requirement_breakdown", "version": 2 }
  ],
  "mcpServices": [
    { "mcpServiceId": "mcp_file_reader", "version": 1 }
  ],
  "knowledgeBases": [
    { "knowledgeBaseId": "kb_product_rules", "version": 4 }
  ],
  "modelConfig": {
    "provider": "openai",
    "model": "gpt-4.1",
    "temperature": 0.2
  },
  "interactionMode": "ask_then_confirm",
  "outputSchema": {}
}
```

配置原则：

- 节点引用资产版本，不直接复制资产内容。
- 所有输入变量必须来自上游节点或触发上下文。
- 所有输出变量必须显式声明。
- MCP 服务必须先授权给该智能体或工作流。
- 知识库必须先授权给该智能体或工作流。
- 输出 Schema 必须用于结果校验。

## 4. Skills 装配

Skill 是提示词和方法论层面的能力增强，不直接等同于工具调用。

一个 Skill 建议包含：

- Skill 名称
- Skill 描述
- Skill 版本
- 适用场景
- Prompt 片段
- 输入要求
- 输出建议
- 禁用规则
- 权限范围

### 4.1 Skill 注入方式

Skill 不建议简单拼接成一大段文本，而应按用途分类注入。

推荐分类：

- `roleGuidance`：角色和方法论。
- `taskGuidance`：任务执行步骤。
- `outputGuidance`：输出格式要求。
- `riskGuidance`：风险和边界提醒。
- `questionGuidance`：追问策略。

Prompt Composer 根据智能体节点配置，把多个 Skills 合并到对应区域。

### 4.2 Skill 冲突处理

多个 Skills 可能出现要求冲突。

处理策略：

- 节点级系统提示词优先级最高。
- 智能体模板提示词次之。
- Skills 按配置顺序合并。
- 如果 Skill 声明互斥标签，保存配置时直接阻止。
- 运行时记录最终启用的 Skill 版本。

## 5. 知识库 / RAG 装配

知识库用于给智能体提供企业内部上下文。

### 5.1 检索时机

推荐在模型调用前完成检索：

```text
输入变量 + 用户问题 + 节点任务描述
  -> 生成检索查询
  -> 查询知识库
  -> 返回引用片段
  -> 注入 Prompt
```

### 5.2 检索记录

每次知识库检索必须记录：

- 知识库 ID 和版本
- 查询文本摘要
- 命中文档
- 命中片段摘要
- 相似度分数
- 是否被注入 Prompt
- 操作人或运行实例

### 5.3 引用展示

如果最终结果引用了知识库，运行态页面应展示引用来源。

业务用户看到：

- 文档名称
- 片段摘要
- 更新时间

管理员看到：

- 检索参数
- 相似度
- 片段 ID
- 注入上下文长度

## 6. MCP 工具调用

MCP 是外部工具和系统连接能力。

典型 MCP：

- 文件读取
- 数据库查询
- 邮件发送
- OA 创建流程
- IM 消息
- Webhook
- 文档生成

### 6.1 工具暴露原则

模型不能看到所有工具，只能看到当前节点允许使用的工具。

工具列表由以下条件共同决定：

- 当前用户权限
- 当前工作流权限
- 当前智能体权限
- 当前 MCP 服务授权范围
- 当前环境是否生产
- 节点配置是否启用该工具

### 6.2 工具调用流程

```text
模型请求调用工具
  -> Agent Runtime 捕获 tool call
  -> MCP Tool Gateway 校验权限
  -> 参数 Schema 校验
  -> 注入服务端凭证
  -> 调用 MCP 服务
  -> 记录审计日志
  -> 返回脱敏结果给模型
  -> 模型继续生成结果
```

### 6.3 高风险工具

高风险 MCP 必须支持二次确认或人工审批。

示例：

- 发送邮件
- 创建 OA 流程
- 写入数据库
- 删除文件
- 批量推送消息

如果需要确认，节点进入暂停状态：

```text
tool_call_requested -> paused_for_approval -> approved -> tool_call_executed
```

## 7. Prompt 组装

Prompt Composer 负责把多个来源装配成最终请求。

推荐结构：

```text
系统角色
安全边界
智能体任务
节点目标
输入变量
已启用 Skills
知识库引用
可用工具说明
输出 Schema
交互模式
```

### 7.1 输入变量注入

输入变量必须带来源和类型，不要只拼接值。

示例：

```text
变量：project_info
来源：用户输入节点「提交项目资料」
类型：object
内容：...
```

### 7.2 输出 Schema 注入

如果节点后续要进入条件分支或交付节点，输出必须结构化。

示例：

```json
{
  "req_list": ["string"],
  "risk_level": "number",
  "need_user_confirm": "boolean",
  "questions": ["string"]
}
```

## 8. 交互模式

智能体节点支持三种主要交互模式。

### 8.1 一次性输出

适合稳定、低风险任务。

流程：

```text
调用模型 -> 校验输出 -> 写入变量 -> 进入下一节点
```

### 8.2 追问确认

适合信息不完整或需要用户确认的任务。

流程：

```text
调用模型
  -> 判断是否需要追问
  -> 暂停等待用户回复
  -> 多轮追问
  -> 用户确认
  -> 生成最终总结
  -> 写入变量
  -> 进入下一节点
```

必须配置：

- 最大追问轮次
- 超时时间
- 用户确认动作
- 默认继续策略

### 8.3 分析后暂停

适合 AI 先分析，再让用户补充或修正。

流程：

```text
调用模型生成初步分析
  -> 暂停
  -> 用户补充信息
  -> 再次调用模型或直接合并
  -> 写入最终变量
  -> 进入下一节点
```

## 9. 输出解析与校验

模型输出不能直接进入下游节点，必须经过 Output Parser。

校验内容：

- 是否符合 JSON Schema。
- 必填字段是否存在。
- 字段类型是否正确。
- 数值范围是否有效。
- 文件变量是否真实存在。
- decision 类型是否属于允许枚举。

失败策略：

- 自动重试一次，附带校验错误让模型修正。
- 仍失败则节点失败或暂停等待人工处理。
- 所有失败原因写入 `NodeRun`。

## 10. 审计与可观测

每次智能体运行必须记录：

- WorkflowRun ID
- NodeRun ID
- 智能体模板版本
- Skills 版本
- MCP 版本
- 知识库版本
- 模型供应商和模型
- 输入变量摘要
- 输出变量摘要
- Token 用量
- 耗时
- 工具调用记录
- 知识库检索记录
- 用户追问记录
- 错误和重试记录

敏感内容要脱敏后展示，原始内容按权限控制访问。

## 11. 推荐数据对象

建议至少保留以下运行记录对象：

- `AgentRun`
- `AgentMessage`
- `AgentToolCall`
- `AgentKnowledgeHit`
- `AgentSkillSnapshot`
- `AgentOutputValidation`
- `AgentInteractionEvent`

这些对象可以先作为 `NodeRun` 的扩展表或 JSON 字段，后期再独立成表。

## 12. 第一阶段实现建议

第一阶段先实现最小闭环：

1. 智能体模板配置。
2. Skill 文本片段装配。
3. MCP 工具注册和只读调用。
4. 知识库检索接口占位。
5. 一次性输出。
6. 追问确认。
7. 输出 JSON Schema 校验。
8. 工具调用审计。
9. 运行态展示工具调用和变量快照。

暂缓：

- 多模型自动路由。
- 复杂 Agent 规划。
- 自动选择 Skills。
- 高级 RAG rerank。
- 插件市场。

## 13. 设计结论

Agentum 的 AI 交互应该被设计成“可配置的运行管线”，而不是一次模型请求。

关键顺序是：

```text
变量准备
  -> 资产解析
  -> 权限校验
  -> Skills 装配
  -> 知识检索
  -> MCP 工具声明
  -> Prompt 组装
  -> 模型调用
  -> 工具调用
  -> 输出校验
  -> 暂停或继续
  -> 变量落库
  -> 审计记录
```

这个管线稳定后，前端才能可靠展示“这个智能体为什么这样做、用了什么能力、调用了什么工具、结果如何进入下一步”。
