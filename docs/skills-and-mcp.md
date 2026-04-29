# Skills 与 MCP 推荐

## 1. 设计原则

Agentum 的 Skills 和 MCP 不应只是“工具列表”，而应是可治理、可复用、可审计的企业能力资产。

区别：

- Skill：提示词和方法论层面的能力增强。
- MCP：外部系统、数据源或工具调用能力。
- 智能体模板：将提示词、Skills、MCP、模型、输出格式组合后的可复用执行单元。

运行时装配和调用细节见 [ai-interaction-runtime.md](./ai-interaction-runtime.md)。

## 2. 推荐内置 Skills

### 2.1 通用基础 Skills

| Skill | 作用 |
| --- | --- |
| 文档摘要 Skill | 对长文档生成结构化摘要 |
| 信息提取 Skill | 从文本、表格、附件中提取字段 |
| 结构化输出 Skill | 将自然语言结果转成 JSON / 表格 |
| 追问澄清 Skill | 判断信息是否足够，不足时生成追问 |
| 风险识别 Skill | 识别流程、合同、需求中的风险点 |
| 质量评分 Skill | 对智能体输出进行完整性和质量评分 |
| 报告撰写 Skill | 生成正式报告、方案、纪要 |
| 语言润色 Skill | 将草稿调整为正式业务表达 |

### 2.2 业务场景 Skills

| Skill | 作用 |
| --- | --- |
| 需求拆解 Skill | 将需求文档拆解为功能点、边界、风险 |
| 合同审查 Skill | 审查合同条款、风险、缺失信息 |
| 项目立项 Skill | 生成项目背景、目标、范围、收益 |
| 竞品分析 Skill | 汇总竞品信息并形成对比 |
| 用户调研 Skill | 生成问卷、整理反馈、输出结论 |
| 会议纪要 Skill | 从会议记录中生成结论和待办 |
| 数据分析解释 Skill | 将数据指标转成业务解释 |
| 审核判断 Skill | 根据规则输出通过、驳回或需补充 |

### 2.3 开发协作 Skills

| Skill | 作用 |
| --- | --- |
| 代码审查 Skill | 发现缺陷、风险和测试缺口 |
| 技术方案评审 Skill | 评估方案复杂度、风险和边界 |
| 接口设计 Skill | 生成或检查 API 字段和错误码 |
| 数据模型评审 Skill | 检查表结构、索引和实体关系 |

## 3. 推荐 MCP 服务

### 3.1 企业系统 MCP

| MCP | 作用 |
| --- | --- |
| 邮件 MCP | 发送邮件、读取指定邮箱回执 |
| IM MCP | 推送飞书、钉钉、企业微信消息 |
| OA MCP | 创建审批流程、查询审批结果 |
| 日历 MCP | 创建会议、查询日程 |
| 通讯录 MCP | 查询组织、人员、角色 |

### 3.2 数据与文件 MCP

| MCP | 作用 |
| --- | --- |
| 数据库查询 MCP | 查询业务数据库，只读优先 |
| 文件存储 MCP | 上传、下载、读取企业文件 |
| 文档生成 MCP | 生成 Word / PDF / Excel |
| 表格处理 MCP | 读取和写入 Excel |
| 知识库检索 MCP | 查询企业知识库和制度库 |

### 3.3 外部信息 MCP

| MCP | 作用 |
| --- | --- |
| Web 搜索 MCP | 搜索公开信息 |
| 网页读取 MCP | 读取指定网页内容 |
| API 调用 MCP | 调用已登记的外部 API |
| 代码仓库 MCP | 读取 Git 仓库、PR、Issue |

## 4. 推荐智能体模板

| 智能体模板 | 推荐装配 |
| --- | --- |
| 需求分析智能体 | 需求拆解 Skill、追问澄清 Skill、文档摘要 Skill |
| 风险评估智能体 | 风险识别 Skill、审核判断 Skill、质量评分 Skill |
| 数据提取智能体 | 信息提取 Skill、结构化输出 Skill、数据库查询 MCP |
| 报告组装智能体 | 报告撰写 Skill、语言润色 Skill、文档生成 MCP |
| 审核判断智能体 | 审核判断 Skill、风险识别 Skill |
| 交付执行智能体 | 文档生成 MCP、邮件 MCP、OA MCP、IM MCP |

## 5. 第一阶段建议内置清单

第一阶段不需要做太多，先保证闭环。

推荐内置 Skills：

- 文档摘要 Skill
- 需求拆解 Skill
- 信息提取 Skill
- 追问澄清 Skill
- 报告撰写 Skill
- 风险识别 Skill
- 质量评分 Skill

推荐内置 MCP：

- 文件上传/读取 MCP
- 文档生成 MCP
- 邮件发送 MCP
- 数据库只读查询 MCP
- Webhook 回调 MCP

推荐内置智能体模板：

- 需求分析智能体
- 数据获取智能体
- 报告组装智能体
- 审核判断智能体

## 6. 当前 Codex 环境可用 Skills 参考

当前开发环境中可使用的能力包括：

- `imagegen`：生成或编辑图片资产。
- `openai-docs`：查询 OpenAI 官方文档。
- `plugin-creator`：创建 Codex 插件结构。
- `skill-creator`：创建或更新 Codex Skill。
- `skill-installer`：安装 Codex Skill。
- `browser-use:browser`：使用内置浏览器测试本地页面。

这些能力可以帮助开发 Agentum，但它们不是 Agentum 产品内置资产。Agentum 自己的 Skills / MCP 需要按企业业务能力重新设计和注册。
