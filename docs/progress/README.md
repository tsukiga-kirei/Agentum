# 当前进度与后续计划

更新时间：2026-06-26（阶段一框架与基础治理完成验收；OA、PDF、IM 等客户差异较大的通道适配调整为后续个性化集成阶段）。

本文档只记录当前施工状态、阶段计划和下一步任务。长期规范、系统说明和架构设计分别维护在：

- [开发规范](../development-standards.md)
- [系统详细梳理介绍](../system-overview.md)
- [能力—流程—权限治理](../capability-workflow-governance.md)
- [AI 运行态接入说明](../ai-runtime-integration.md)
- [Skill 与 MCP 运行机制](../skill-mcp-runtime-guide.md)
- [运行态异步执行设计（MQ + Redis）](../runtime-async-execution-design.md)
- [流程创建与运行态节点检查说明](./workflow-creation-runtime-node-guide-2026-06-11.md)

## 1. 当前阶段

当前已完成 **阶段一：框架与基础治理**，后续进入 **阶段二：稳定化、平台增强与个性化集成**。

阶段一目标不是一次性做完所有智能体能力，而是先搭出可持续扩展的框架。当前已完成：

```text
认证与租户上下文
  -> 用户 / 部门 / 角色 / 权限基础
  -> 工作台与流程设计
  -> 能力资产治理
  -> 工作流运行和暂停恢复
  -> 审计与交付闭环
```

第一阶段验收闭环：

```text
用户输入 -> 智能体分析/追问 -> 并行子智能体获取数据 -> 合并组装 -> 人工审核 -> Word/邮件/Webhook 交付 -> 审计证据链
```

阶段一完成判断：

- 多租户认证、三类登录入口、角色切换、租户上下文和菜单驱动已落地。
- 系统管理、租户管理、能力资产、流程设计、业务工作台和运行审计已形成真实 API 驱动的基础工作台。
- 工作流草稿、变量声明、发布校验、不可变版本、协作权限和发起入口治理已打通。
- 运行态已采用 RabbitMQ + Redis Stream 异步执行，支持刷新恢复、中断、重新执行、失败恢复、单智能体和智能体集群执行。
- 智能体节点可通过 ReAct / Function Calling 调用模型、Skill 和 MCP，并写入模型调用、MCP 调用、变量快照、Token 用量和交付记录。
- 系统内置交付已覆盖邮箱、Webhook 和 Word 文档，满足阶段一“可交付、可追溯”的通用闭环。
- OA、PDF、IM、数据库写入等交付通道强依赖客户系统、审批口径、版式规范和安全策略，归入后续个性化集成，不再作为阶段一完成前置条件。

本轮设计决策：

- 知识库 / RAG 从第一阶段移除。
- 能力资产保留智能体模板、Skills、MCP、提示词模板和交付能力。
- 能力分配口径调整为可用能力池模型：不再区分查看 / 使用 / 执行 / 管理动作，系统管理配置租户能力池，租户管理分配给用户 / 部门 / 角色，前端可见即能用。
- 租户管理的页签分配和能力分配改为“分配卡片”模型：一次分配包含业务名称、对象集合和页签 / 能力集合，后端用 `grant_group_id` 与 `grant_group_name` 记录同一批分配明细，前端不再展示动作码或角色编码。
- 权限和审计提示从能力资产页弱化，主要放入租户管理、系统管理和运行安全策略。
- 新增系统管理，承载租户、底层模型供应商、全局 MCP / Skills / 提示词模板 / 交付能力登记测试，以及租户可用能力池和模型分配。
- 区分业务审核、运行监控和运行审计：审计页只读，不承接暂停恢复动作。
- 明确当前页面都是阶段性产品视图抽象，不等同于领域模型或数据库边界，后续可随开发继续拆分、合并或改名。
- 运行审计里的执行链路属于聚合视图，应依托运行记录、节点记录、等待事件、审计事件、MCP 调用、交付记录和变量快照生成。
- 能力资产页不应保留重复的泛化创建入口；每类资产独立提供创建入口，辅助区域后续优先展示引用关系和治理问题。
- 补充能力源码目录边界：`capabilities/skills/` 存放产品运行时 Skill，`capabilities/mcp-servers/` 存放自研 MCP Server，数据库和资产页承接登记、版本、租户可用能力池、租户内分配和审计；提示词模板能力保留在前端配置和能力资产链路中，不再维护独立源码目录。
- 交付能力进一步区分系统内置和自定义适配器：邮箱发送和 Word 文档生成作为系统内置能力由 API 原生实现，`capabilities/delivery/` 只保存自定义交付适配器 Manifest、源码和测试材料。
- 当前进度文档独立放入 `docs/progress/`，不混入长期主文档。
- 数据库使用 PostgreSQL，版本随代码通过 Flyway 迁移同步。
- Flyway 迁移目录已拆为 `schema` 与 `devdata`：真实结构和结构性迁移放 `schema`，本地演示账号、演示租户、演示能力和开发期兼容清理放 `devdata`；开发阶段允许在确认引用后删表删字段，优先保证功能模型清晰。
- 登录页采用多租户入口：业务用户和租户管理入口必须选择租户，系统管理入口不绑定租户；认证 API 需要把 `tenantId` 和活跃角色写入会话或 JWT。
- 补齐首次部署初始化：系统无任何用户时前端进入 `/setup` 创建首个系统管理员；后端通过 `GET /api/auth/bootstrap-status` 和 `POST /api/auth/bootstrap` 只在零用户状态下开放初始化。
- 前端引入 Ant Design 作为复杂表单、选择器、表格、弹窗和权限配置类组件库；Tailwind CSS 与本地 CSS 变量继续负责布局、主题和 Agentum 自有视觉风格。
- 注释和日志纳入强制治理：权限、租户上下文、认证、组织成员写入、外部调用和失败分支必须补中文注释与脱敏日志，禁止输出密码、Token、密钥和敏感原始响应。
- 租户管理确定为租户内管理入口，阶段一已承担人员组织、资源范围和能力分配；第一阶段保留业务用户、租户管理、系统管理三类基础入口。
- 系统管理员 / 租户管理员路由分层、JWT + 租户上下文 + 角色校验、部门 / 角色 / 成员管理和租户模型配置，是 Agentum 权限设计的重要基础；平台级管理与租户内管理必须保持清晰边界。
- 当前前端页面仍是抽象卡片和说明面板，用于表达产品信息层级，不是生产最终形态；后续系统管理、租户管理、能力资产、审计等页面都要改造成“左侧大菜单 + 模块内页签 / 顶层菜单 + 列表 / 详情 / 表单 / 授权矩阵 / 审计记录”的真实工作台。
- 页面之间必须保留勾稽逻辑：系统管理的租户可用能力池决定租户管理可分配能力，租户管理的角色、资源范围和能力池分配决定业务工作台、能力资产和流程设计可见内容，流程设计引用的资产版本必须能在运行审计中追溯。
- 权限模型补充角色 / 租户切换：一个用户可拥有多个三大登录入口角色，切换后重新计算活跃租户、左侧菜单、模块内页签、资源范围和可用能力池；后端仍按 token、租户、登录入口角色、部门、租户自定义角色、人员分配、资源范围和运行安全策略复核。
- 业务用户是第一重登录入口，可承载业务工作台、流程设计、能力资产、运行审计等业务侧模块；是否可见具体模块、页签、流程、资产和能力池，由租户管理员按用户、部门、租户自定义角色配置。系统管理员只管理平台级租户、全局能力登记测试、模型供应商、租户可用能力池和模型分配，不预置租户内业务角色模板。
- 页面设计思路调整为“按系统管理逐层向上统一”：管理台页面优先对齐系统管理骨架（页头 -> 模块切换 -> 内容卡片 -> 列表/详情/表单），再填充各模块业务内容。
- 管理台列表能力进入分页阶段：后端将补统一分页组件（`shared.pagination`），接口统一 `page/size/sort` 参数与分页响应模型，前端统一分页交互。
- 工作流设计从自由画布收敛为左侧步骤积木 + 右侧积木配置：先保留草稿、节点、依赖和设计权限边界，再逐步把契约演进为输入节点、单智能体节点、智能体集群节点和交付节点。
- 流程设计页签调整为“总览 / 协作开放 / 我的流程”；发布校验收敛到流程详情抽屉动作，避免和流程列表重复。
- 流程设计页按通用业务闭环重做交互心智：先配置用户输入，再由单智能体或智能体集群结合权限内 Skill、MCP、提示词模板和智能体模板处理，最后进入交付能力；事实类数据只能重新获取，模型文本可重新生成或追问修改。
- 统一分页组件已落地到后端 `shared.pagination`：系统管理租户、模型供应商、全局能力列表改为后端分页，工作流草稿和租户内角色分页统一复用分页工厂与排序白名单；前端管理台分页统一使用 Ant Design Pagination 并补深色模式样式。
- 流程设计的默认系统触发、可添加积木模板和变量元数据已从前端虚拟常量迁移到后端 `designer-catalog` 接口；前端只负责渲染和保存草稿结构。
- 设计态取消“暂停点”概念，流程按积木顺序推进；`pause_point_count` 字段与 `system_user_roles` 废弃表已在开发期迁移中删除。

## 2. 已完成内容

### 2.1 文档

已完成：

- 根 README 重写为更标准的项目入口。
- 长期文档收敛为三份主文档。
- 当前进度与后续计划独立放入 `docs/progress/README.md`。
- AGENTS 入口更新为新的文档结构。
- 补充 `capabilities/` 能力源码目录规划，区分产品运行时 Skill、自研 MCP Server、提示词模板、交付适配器和资产治理记录。
- 明确租户管理是租户内管理入口，系统管理是平台级入口，并将权限分层设计写入长期文档。
- 补充生产页面改造原则：当前卡片式静态页仅为概览说明和风险摘要，后续需要按左侧大菜单、模块内页签、列表、详情、授权矩阵和审计记录收敛。

需要持续维护：

- 每次阶段变化后更新本文档。
- 每次新增长期设计时优先合并到三份主文档。

### 2.2 前端

已完成静态演示：

- 登录页，包含业务用户、租户管理、系统管理入口；业务和租户管理入口已有租户选择占位，系统管理入口隐藏租户选择。
- 工作台壳层、主题切换、侧栏导航。
- 业务工作台：待办、运行态摘要、可用流程模板。
- 流程设计：工作流列表、阶段积木编排、节点配置面板、变量引用演示。
- 能力资产：智能体模板、Skills、MCP 台账。
- 租户管理：人员组织、资源范围和能力分配。

本轮已调整：

- 业务工作台顶部按钮应按当前用户角色显示。
- 能力资产页移除知识库展示，新增提示词模板和交付能力资产。
- 新增系统管理入口，展示租户、模型供应商、全局能力和租户可用能力池关系。
- 流程设计中的并行节点改为“多个子智能体并行执行”的表达。
- 流程设计详情页已从自由画布重做为左侧步骤积木和右侧配置面板，支持添加输入节点、单智能体节点、智能体集群节点和交付节点，并支持步骤上移、下移和删除。
- 业务工作台明确待办与运行态摘要的区别，可用流程模板改为发起流程语义。
- 流程设计右侧配置按积木类型展示输入框、智能体能力、集群拼接规则、交付方式和输入输出参数，避免笼统说明式面板。
- 流程设计积木配置已接入能力资产：单智能体和智能体集群可引用权限内智能体模板、提示词模板、MCP 和 Skill，交付节点可引用交付能力；MCP 和 Skill 不再来自前端硬编码选项。
- 流程设计视觉完成一轮收敛：近期协作流程卡片增加白边，详情抽屉展示流程概览，积木设计页默认进入第一个积木；没有积木时展示搭建引导，不再展示无意义提示条和暂停点信息。
- 能力资产页移除“流程导入建议”，改为资产新增入口和每类资产的添加动作。
- 审计运行相关 demo 页面已下线，相关入口和页签分配已从当前阶段移除，避免提前暴露下一阶段范围。
- 租户管理页收敛为人员组织、资源范围和能力分配，避免用需求配置、运行安全策略、审计可见性等未来概念提前占用页面。
- 记录页面抽象原则：当前静态页服务于解释角色任务和信息层级，不作为长期模块边界；执行链路和资产创建入口等抽象后续需按真实数据来源和具体模块能力继续收敛。

### 2.4 权限架构升级

已完成第一、二阶段：

- 新增 Flyway 迁移 `V202605100002`：创建 `user_role_assignments`（统一角色分配表）和 `tenant_org_roles`（租户内自定义角色表），并从 `system_user_roles` + `user_memberships` 迁移数据。
- 新增 `UserRoleAssignmentEntity` / `TenantOrgRoleEntity` JPA 实体和仓储。
- 重写 `AuthService`：登录、/me、角色切换都改为基于 `user_role_assignments`，返回完整角色列表和菜单。
- 新增 `MenuService`：根据系统角色（system_admin / tenant_admin / business）计算左侧菜单。
- 菜单分配确认为两层：系统管理员 → 系统管理，租户管理员 → 租户管理；业务用户进入业务侧模块集合（业务工作台、流程设计、能力资产），后续由租户管理按用户、部门、租户自定义角色继续过滤模块、页签、资源范围和能力池。
- 新增 `PUT /api/auth/switch-role`：切换角色后重签 token，返回新的活跃角色和菜单。
- `LoginResponse` 扩展为包含 `roles[]`、`activeRole`、`permissions[]`、`menus[]`。
- `AuthTokenClaims` / `CurrentUserPrincipal` 新增 `roleAssignmentId`。
- 前端 `authStore` 新增 `roles`、`activeRole`、`permissions`、`menus` 状态和 `switchRole` 动作。
- `WorkbenchShell` 菜单改为后端 `menus` 驱动，移除硬编码 `visibleFor`。
- 新增 `RoleSwitcher` 组件：右上角展示用户所有可用角色并支持一键切换。
- TypeScript 类型检查通过。
- 登录页补齐租户下拉占位，并在前端模拟认证状态中保存 `tenantId`、租户名称和租户编码，为后续接入公开租户列表和登录 API 留出契约位置。
- 引入 React 版 Ant Design，并将登录页租户下拉替换为组件库 Select；Vite 已配置 vendor 分包，避免组件库进入主入口 chunk。
- 前端登录链路已接入后端认证 API：租户下拉调用 `/api/public/tenants`，登录调用 `/api/auth/login`，刷新恢复调用 `/api/auth/me`，登出调用 `/api/auth/logout`；本地 mock 登录数据已移除。
- 认证会话已升级为 Access Token + Refresh Token：Access Token 默认 15 分钟，Refresh Token 默认 30 天并以 HttpOnly Cookie 传递；数据库只保存摘要，刷新时单次轮换，登出与角色切换会吊销旧 Refresh Token。前端统一处理并发 401、自动续签与续签失败退登，认证过滤器错误响应补齐 UTF-8，登录页“记住账号”只保存用户名且不影响 Token 生命周期。
- 租户管理页的人员组织标签已接入租户人员组织概览 API，展示后端返回的成员、部门、角色和成员关系摘要。
- 租户管理页新增成员弹窗已接入后端新增成员接口，提交后刷新租户人员组织概览。
- 租户管理页新增部门弹窗已接入后端新增部门接口，支持选择上级部门并刷新租户人员组织概览。
- 租户管理页已从侧边二级按钮调整为模块内页签，并收敛为当前真实推进的人员组织、资源范围和能力分配。
- 系统管理页已改为模块化工作台：平台概览统计、租户状态、模型供应商与全局能力注册、租户可用能力池和模型分配；数据来自 `/api/system/*`（需 system_admin 登录），并补齐深色模式弹窗表单可读性。
- 租户管理页已对齐系统管理页的页面骨架与样式层级（统一页头、卡片容器、模块说明与页签风格），保留租户内治理信息架构。
- 租户管理页的人员组织已改为部门树 + 成员表结构，按部门筛选成员；成员列表默认只读展示部门、角色和状态，通过右侧编辑按钮进入单人成员编辑，并支持启用 / 禁用成员关系，避免表格浏览态误触；资源范围页签已接入租户内自定义角色 API，并新增当前租户可用 MCP、Skill、提示词模板和交付能力的能力池分配配置，不再只停留在页面权限。
- 租户管理继续细化为人员组织、角色维护、资源范围与能力分配三个页签：人员组织保留左侧部门目录树，部门和角色停用收进编辑弹窗；部门编码改为后端自动生成；资源范围保留页签分配，并新增按角色、部门、人员混合多选的能力池分配规则。
- 页签分配已从旧的“规则名称 / 自定义角色”表单拆出为独立配置：按角色、部门、人员混合多选主体，再以卡片方式多选页签；能力分配同样改为主体 + 能力卡片多选，分配后可见即能用，页面空态去掉重复的“创建第一条授权”按钮。
- 租户组织生命周期勾稽（P0/P1）：停用部门、租户内角色、成员关系前，若仍被 `page_grants` 或 `resource_grants` 引用则阻断并返回 `ORG_PRINCIPAL_HAS_*`；调整部门上级时检测组织环（`ORG_DEPARTMENT_CYCLE`）；新增 `GET .../principals/{type}/{id}/grant-usage` 供停用前预检查。
- 租户管理资源分配页已改为后端分配卡片：页签分配和能力分配均支持编辑卡片名称、删减对象和资源；卡片标题显示业务名称，具体页签 / 能力和分配对象分行展示，页面移除提示性说明文案、动作标签和角色编码展示。
- 流程设计列表页已接入后端工作流草稿 API：按当前租户加载分页草稿、新建草稿落库，页面风格继续沿用系统管理 / 租户管理的工作台骨架。
- 流程设计详情页已接入草稿详情与保存 API：已有草稿按阶段积木回显后端节点、依赖和变量声明；空草稿只载入后端系统触发节点，设计者按需从后端模板添加输入、单智能体、智能体集群和交付积木；列表页同步更新积木数。
- 流程设计列表页的“发布校验”已接入真实 API：后端返回结构化校验结果，当前先覆盖图为空、触发节点、交付节点、节点进出边、循环、不可达节点、输入变量无法由上游解析和重复输出变量。
- 流程设计页已重塑为共享协作流程库：总览改为可跳转功能卡，协作开放通过 `scope=shared` 展示他人开放给当前用户参与设计的流程，我的流程通过 `scope=mine` 只筛当前创建人；流程卡点击先打开右侧详情抽屉，再进入积木设计或执行发布校验。
- 流程设计保存时已同步变量声明：变量名称、类型、来源节点、敏感标记和交付标记随草稿结构一起读写，变量面板从后端声明恢复，不再只依赖前端临时推导。
- 流程输入节点支持按输入框配置“是否必填”：新字段默认必填，运行页展示必填 / 选填状态并只拦截未填写的必填项；后端按发布快照再次校验，旧流程字段继续按必填处理。
- 流程设计已接入正式发布 API：发布时再次执行后端校验，并把当前节点、连线和变量声明冻结到 `workflow_versions` 不可变快照；已发布草稿再次编辑会回到草稿态，避免未发布改动混淆当前正式版本。
- 流程设计模板完成后端化：新增 `GET /api/tenants/{tenantId}/workflows/drafts/designer-catalog`，统一下发系统触发节点、四类可添加积木和变量元数据；前端不再维护授信类起步模板或默认变量表。
- 系统管理页完成一轮可用性优化：平台概览新增模型提供商一览；模型供应商支持编辑、默认模型必填、API Key 填写与“测试连接”占位；全局能力支持编辑，测试结果只通过消息提示展示；租户模型分配支持取消和重新启用。
- 系统管理模型供应商测试已从前端占位改为后端真实接口：新增模型密钥字段加密服务，API Key 加密保存到供应商配置，测试时由后端解密并访问供应商模型列表接口，前端只展示脱敏结果摘要。
- 系统管理补齐租户启用关系的状态治理：全局能力或模型供应商仍被租户启用时，禁止删除或改回草稿；草稿状态的能力或模型供应商不能启用给租户。
- 能力资产页开始接入真实治理链路：新增租户自建能力资产表和租户侧能力资产 API，页面改为“总览 / 对我开放 / 我的能力”页签；“对我开放”只展示租户管理已分配给当前用户、部门或角色的能力，并支持搜索与能力类型筛选，卡片与详情抽屉以只读方式展示分配范围与当前版本；“我的能力”支持能力类型和草稿 / 已发布筛选，总览待完善草稿可直接进入草稿筛选；底层仍复用系统管理的 `system_capabilities`、租户可用能力池 `tenant_capability_grants` 和租户管理分配 `resource_grants`，自建能力先以草稿形式沉淀到当前租户和创建人名下。
- “我的能力”已收敛为草稿发布模型：入口文案改为“新建能力草稿”，业务用户只可创建提示词模板和智能体模板；草稿详情支持编辑、发布和删除，提示词模板保存提示词正文，智能体模板只能从当前主体已开放的 Skill/MCP 与系统提示词组合，发布前后端重新校验引用权限。Skill、MCP 和交付能力暂不开放用户自建；后续接入引用关系后，已被流程使用的能力必须禁止删除。
- 能力资产「对我开放」页签优化：移除「加入节点引用」操作和「当前状态」展示，卡片改显当前版本；系统能力详情抽屉的说明与提示词内容改用只读区块样式，与抽屉整体视觉一致。
- 智能体模板草稿支持选择已有提示词模板：新建与编辑时可从「对我开放」或本人已发布的提示词模板引用并自动带入正文，也可继续自定义系统提示词；后端保存 `systemPromptTemplateId` 并在保存/发布时校验引用边界，草稿不能被引用。
- 已发布能力支持改回草稿：创建人可将已发布能力改回草稿继续编辑；已发布详情抽屉只读展示，通过「改回草稿」恢复编辑态。
- 用户自建能力和流程已统一为双权限模型：读取 / 使用与内容编辑分别支持仅自己、指定同事、全体同事；编辑自动包含读取，只有创建者可以配置权限和删除资源。
- 能力资产「对我开放」和流程设计「协作开放」展示当前用户有读取或编辑权限的内容并标注访问级别；流程「我的流程」补充新建入口，详情支持协作者编辑说明，流程内容按节点类型使用不同图标颜色且不再重复展示输出变量。
- 协作编辑流程保存与发布时按当前操作者重新校验所有引用能力：系统能力必须已分配给操作者，用户自建能力必须已发布且操作者拥有读取权限，流程编辑权限不会向下传递能力权限。
- 业务工作台运行态已接入第一版：新增 `workflow_runs`、`workflow_node_runs`、`workflow_waiting_events`、`workflow_run_events` 表，`/api/tenants/{tenantId}/workbench/summary` 返回真实待办和最近运行；创建任务列表改为展示全部未收回且已发布流程，并通过 `visibility`、`canLaunch`、`launchBlockedReason` 标记当前账号是否有发起权限。
- 业务工作台任务处理页已从本地 `buildRuntimePreview` mock 切换到后端 `WorkflowRun` API：新增创建运行、任务中心分页、运行详情和待办完成接口；发起任务后按发布版本快照生成节点链路，用户输入 / 人工审核节点形成真实待办，任务记录会排除仍有打开待办的运行实例，避免待办与记录重复展示。
- 后端运行态已从占位输出升级为 `WorkflowRuntimeExecutor` + `AgentRuntimeService` 分发：触发、条件、汇聚节点本地完成；单智能体和智能体集群调用租户已启用模型分配，支持 OpenAI / 通义兼容与 Azure OpenAI Chat Completions；智能体节点已改为 ReAct/Function Calling 模式，模型可自主调用当前节点可用的 Skill 读取工具和 MCP SSE `tools/call`，最后通过 `final_answer` 提交 Markdown 结论；交付节点支持系统内置邮箱、Webhook 和 Word 文档交付能力。
- 新增运行态勾稽表 `variable_snapshots`、`model_call_logs`、`mcp_call_logs`、`delivery_records`，节点完成后写入变量快照，模型 / MCP / 交付调用均关联租户、运行、节点、流程定义和发布版本；执行失败会把运行与当前节点标记为失败并写入 `node_failed` 事件，不再返回“尚未接入”的占位输出。
- MCP 多工具运行契约已补齐：系统管理连通性测试会持久化 `tools/list` 的真实工具名、描述和输入 Schema，智能体按远程工具逐项获得可调用定义与必填参数；移除以平台能力编码回退远程工具名的错误路径，并将 MCP `isError=true` 正确记为失败及推送失败事件。可恢复的工具执行 / 网络失败会作为 observation 回写模型继续回答，权限与配置错误仍终止节点。
- 智能体循环次数已改为流程节点级配置：单智能体和每个集群子智能体分别保存 `maxAgentIterationsPerTurn`，首次执行与每次追问独立重新计数；设计目录由后端下发建议值和平台最大值，运行时不再对旧快照隐式使用固定次数。补充 [Skill 与 MCP 运行机制](../skill-mcp-runtime-guide.md)，明确 Skill 当前只读文本、不执行脚本，以及 MCP 工具发现、参数、失败恢复和审计链路。
- 业务工作台运行详情收敛为真实可操作项：待办节点只显示提交动作，AI / MCP / Skill / 交付节点展示后端输出、调用状态和交付摘要；执行历史去掉与左侧流程轨重复的节点列表，改为选中步骤快照 + 事件时间线；已保存任务可对智能体节点释放重新生成入口，追问追加上下文和后端取消补偿进入阶段二运行监控增强。
- 流程设计落地版本治理方案 C：`workflow_definitions.launch_enabled` 控制业务入口收回/恢复；列表与详情展示 `latestVersionNumber`、`hasUnpublishedChanges`；创建者可删除流程或收回业务入口；业务工作台可发起列表改为「有冻结版本且入口未收回」，与设计态 `status` 解耦；新增 [能力—流程—权限治理](../capability-workflow-governance.md) 文档。
- 企业 SSO 完成 OIDC 第一版骨架：新增租户 SSO Provider 与外部身份绑定表，登录页按租户发现 SSO 身份源，后端生成签名 `state` 与 `nonce` 并完成 OIDC 回调后的本地 token 签发；新增 [企业 SSO 对接说明](../sso-integration.md)，明确业务系统只需按标准 OIDC 提供身份，不承载 Agentum 权限判断。
- 系统内置 Word 文档交付初版已接入流程设计和运行态：系统管理可区分邮件交付与文档交付并配置默认字体、中文字号、行距、缩进、页边距、首行标题对齐、最大文件和保留天数；流程交付节点选择 Word 能力后用交付正文模板生成最终 Markdown，可配置文件名模板、节点级样式和不替换变量的预览 Markdown，并支持设计态导出 docx 样例；运行态将文件写入 MinIO/S3 兼容对象存储并生成 `delivery_records` 文件结果，后台按 `expiresAt` 清理过期对象，业务工作台交付页可按记录下载文档。新增 [Word 文档交付说明](../word-document-delivery.md)。
- 智能体集群节点完成执行方式升级：设计态从“并行 / 顺序”收敛为“协同处理 / 接力处理 / 意图分派”，子智能体支持上下调整顺序；接力处理按顺序校验提示词变量引用，意图分派配置受控分类提示词、意图代码、命中策略、置信度阈值和其他意图处理，运行态只接受设计时白名单 `intentCode`，再执行命中的子智能体，并兼容旧快照 `parallel` / `sequential`。

### 2.3 后端

已完成：

- Spring Boot API 服务骨架。
- PostgreSQL 本地配置。
- Spring Security / JPA / Actuator 依赖。

本轮已推进：

- 引入 Flyway。
- 新增第一版基础迁移：租户、用户、部门、角色、权限策略、模型供应商、系统能力、租户可用能力池配置。
- 新增认证与租户上下文第一批后端实现：统一 API 响应结构、请求 ID、公开租户列表、登录、当前用户、登出、Bearer Token 校验和本地 CORS 配置。
- 建立租户、用户、系统角色、用户租户成员关系和角色的实体 / 仓储边界。
- 新增租户人员组织概览 API：按租户聚合返回成员、部门、角色和成员关系，并增加系统管理员 / 租户管理员访问校验。
- 新增租户成员创建 API：创建用户账号、写入用户租户成员关系，并校验用户名唯一性、部门归属和角色归属。
- 新增租户部门创建 API：写入部门并校验上级部门必须属于当前租户。
- 新增租户成员关系调整 API：支持成员角色调整与部门调整，并校验角色/部门归属当前租户。
- 新增租户内自定义角色 API：支持 `tenant_org_roles` 分页查询、新增、更新状态和页面权限，并校验页面权限范围。
- 新增系统管理 API（`/api/system`）：概览统计、租户列表与状态更新、模型供应商与全局能力注册、租户可用能力池和模型分配；仅 `system_admin` 可访问。
- 租户页签分配和能力分配接口改为卡片语义：新增 `grant_group_id` 迁移，`POST /page-grants`、`PUT /page-grants/{grantGroupId}`、`DELETE /page-grants/{grantGroupId}` 以及能力分配同类接口均按卡片创建、编辑、删除，同时保留明细行服务运行时判权。
- 新增 `TimeConfiguration` 提供可注入的 `Clock`，便于服务层时间与测试。
- 新增 Flyway `V202605100001` 演示种子：模型供应商、系统能力、租户可用能力池示例数据。
- 新增本地开发身份数据迁移，用于跑通系统管理、业务用户和租户管理入口。
- 修复 Gradle 仓库配置冲突，使后端测试可以在临时 Gradle Docker 镜像中执行。
- 新增 Flyway `V202605130001`：建立 `workflow_definitions`、`workflow_node_definitions`、`workflow_edge_definitions`，先承接设计态草稿、节点和依赖。
- 新增工作流草稿 API：分页查询、新建草稿、读取详情、保存节点和依赖，并按租户上下文与流程设计角色复核权限。
- 新增 `WorkflowDesignAccessTest`，覆盖租户管理员、页签分配、普通业务用户和跨租户访问边界。
- 新增工作流发布校验 API：基于已保存草稿结构返回结构化校验结果，并新增 `WorkflowPublishValidatorTest` 覆盖连通性、结构异常、循环和变量依赖错误。
- 新增 Flyway `V202605180001`：建立 `workflow_variable_definitions`，把变量定义从节点字符串数组中拆出；保存草稿结构时同步校验变量命名、类型、来源节点和输出一致性，并新增 `WorkflowVariableDeclarationValidatorTest`。
- 新增 Flyway `V202605180002`：建立 `workflow_versions`；新增正式发布 API，把通过校验的草稿冻结为不可变版本，并新增 `WorkflowDraftServicePublishTest` 覆盖发布成功与校验失败边界。
- 新增工作流设计目录服务 `WorkflowDesignerCatalogService`：后端统一提供设计态积木模板和变量元数据，并补 `WorkflowDesignerCatalogServiceTest` 覆盖模板顺序与默认输出变量。
- 工作流草稿保存已移除设计态暂停点计数：`pause_point_count` 字段从表结构、实体、API 与前端契约中删除。
- 新增统一分页组件：`PageQuery`、`PageableFactory`、`SortWhitelist`，并补排序白名单和分页边界单元测试。
- 系统管理租户、模型供应商、全局能力列表已改为分页接口，OpenAPI 同步分页参数与分页响应模型。
- 系统管理 API 新增模型供应商编辑、系统能力编辑、租户模型分配状态更新接口；模型供应商 API Key 加密保存，查询接口只回显已配置状态，避免在列表、日志和响应中回显明文。
- 系统管理 API 新增 `POST /api/system/model-providers/{providerId}/test`，模型密钥通过通用字段加密服务保存为密文，测试接口只返回连接状态、脱敏摘要、模型 ID 预览和耗时。
- 新增业务工作台 package（`workbench.application` / `workbench.interfaces`）：聚合租户内已发布工作流计数、对当前用户开放的能力资产计数、我的能力草稿计数，以及可发起的已发布工作流分页（含最新版本号、节点数、所有者）；运行态相关字段以空列表 + `runtimeAvailable=false` 返回，并补 `WorkbenchAccess` / `WorkbenchService` 单元测试覆盖访问校验、跨租户拒绝与最新版本号回填路径。
- 运行态异步执行落地（2026-06-10）：新增 `com.agentum.runtime` 包（RabbitMQ 拓扑与命令发布/消费、Redis 执行租约、`RunProgressStreamWriter` / `RunStreamRelayService` Redis Stream 进度写入与 SSE 中继、`StaleExecutionReaper` 超时/失联回收、`RedisRunCancellationGuard` 取消与截止信号）与 Worker 侧 `NodeExecutionService`；新增迁移 `V202606100001`（`workflow_run_execution_jobs`、`workflow_cluster_agent_runs`）；`WorkbenchRuntimeService` 重构为 advance 入队化，删除 `@Async`、内存 `RunStreamEmitterRegistry` 与 `RunExecutionCancellationRegistry`；新增 `interrupt`（节点 `canceled` + 数据清空）、`restart`（整步重跑）、`recover`（保留已成功子智能体只重跑失败部分）端点；智能体集群支持协同处理、接力处理与意图分派，旧快照 `parallel` / `sequential` 兼容映射；模型瞬时错误自动重试（attempt ≤ 3）；节点执行超时由 `AGENTUM_RUNTIME_NODE_TIMEOUT_SECONDS` 控制。前端 `useRunStream` 支持 `replay` 整步回放、`lastEventId` 断线续传与 heartbeat 活性；`TaskRunWorkspace` 进入即执行、刷新无感恢复（activeJob 驱动）、看门狗异常判定；`StepActionBar` 重做「中断执行 / 重新执行 / 恢复进度」互斥按钮矩阵。本地开发必须先 `make dev-infra`。
- 模型 Token 用量闭环（2026-06-19）：流式 Chat Completions 显式请求 usage，模型调用日志增加标准化输入、输出和总 Token 字段；单智能体每条 assistant 回复按本轮全部 ReAct 调用累计展示，多智能体按子智能体/对话轮次展示；运行审计详情展示整次运行累计和逐次调用用量，模型调用台账同步展示 Token 明细。
- 推理模型控制闭环（2026-06-22）：模型供应商新增推理模型标识；流程单智能体和集群子智能体按租户模型分配选择运行模型，仅推理模型可开启深度推理；OpenAI 兼容请求传输 `chat_template_kwargs.enable_thinking`，运行态兼容解析 `reasoning` / `reasoning_content` 并以“深度推理”独立流式展示和留痕。
- Word 文档交付落地（2026-06-15）：新增文档样式快照、轻量 Markdown -> DOCX 渲染器、MinIO/S3 对象存储、设计态预览接口 `/api/tenants/{tenantId}/document-deliveries/preview`、运行态下载接口 `/api/tenants/{tenantId}/delivery-records/{recordId}/download`，并新增演示数据种子 `word_document_delivery`。
- Word 文档交付样式增强（2026-06-22）：系统管理初始化配置和流程交付节点均支持正文、一级至三级标题及表格数字字体；表格首行可选加粗且不再附加底色，框线可关闭，开启时统一为全边框并可选磅数（默认 0.5 磅），表格行距可独立配置；本地演示数据迁移从同层级西文字体初始化复制数字字体。

阶段二继续推进：

- 继续补齐组织、权限等 package 的业务服务和管理 API。
- 系统管理：后续可扩展租户创建、模型供应商编辑、能力版本与审计只读 API。

### 2.4 契约

已完成：

- `packages/shared-contract` 下已有工作流、变量、智能体和 MCP Schema 占位。
- 前端存在临时类型出口 `apps/web/src/types/workflow-contract.ts`。

本轮已推进：

- 智能体 Schema 移除知识库字段。
- 补交付能力、租户可用能力池配置、提示词模板相关字段。
- OpenAPI 补充公开租户列表、登录、当前用户和登出接口契约。
- OpenAPI 补充租户成员关系调整接口契约（角色调整、部门调整）及请求模型。
- OpenAPI 补充租户内自定义角色分页、新增和更新接口契约。
- OpenAPI 修正 `/api/auth/me` 的响应结构，补充角色切换和工作流草稿接口契约。
- 前端临时工作流契约补充 `WorkflowDesignerCatalog`、`WorkflowBrickTemplate` 和 `WorkflowVariableTemplate`，用于承接后端积木模板目录；后续仍需纳入 OpenAPI 统一生成。
- OpenAPI 新增业务工作台路径与 `WorkbenchSummary` / `WorkbenchMetrics` / `WorkbenchPendingTodoRow` / `WorkbenchRecentRunRow` / `WorkbenchAvailableWorkflowRow` / `WorkbenchAvailableWorkflowPageResponse` Schema；前端新增 `apps/web/src/types/workbench.ts` 与 `workbenchApi`，与后端 DTO 字段保持一致。

阶段二契约治理：

- 后续接 OpenAPI / JSON Schema 生成前端类型（系统管理路径已写入 `agentum.openapi.yaml`）。

## 3. 后续开发说明

阶段一完成后，后续开发不再继续扩大基础框架范围，优先围绕“稳定、可验收、可接客户系统”推进。

### 3.1 立即优先级

| 任务 | 说明 | 交付物 |
| --- | --- | --- |
| 阶段一端到端验收 | 固化登录选租户、能力分配、流程发布、发起运行、智能体 / MCP、人工审核、Word / 邮件 / Webhook 交付和审计追溯的标准验收脚本 | 验收脚本、测试数据和验证记录 |
| 契约收口 | 补齐运行态新增接口、审计查询、交付下载和系统管理路径的 OpenAPI 描述，减少前端手写类型漂移 | 更新后的 `agentum.openapi.yaml` 与前端类型对齐 |
| 注释与日志治理 | 继续补权限、租户上下文、运行态、模型 / MCP / 交付失败分支的中文注释和脱敏结构化日志 | 关键路径日志、错误提示和验证记录 |
| 运行态稳定化 | 围绕中断、重新执行、恢复进度、模型瞬时失败、MCP 异常和交付失败补充回归测试 | 后端测试和必要的前端状态回归 |
| 审计可用性增强 | 保持审计页只读边界，补齐运行事件、变量快照、模型/MCP 调用、交付记录的筛选和证据导出能力 | 审计查询、筛选和证据链展示增强 |
| 能力示例 | 至少补一个示例 Skill 和一个示例 MCP Server，用于演示登记、授权、调用和审计链路 | `capabilities/` 示例、说明和测试材料 |

### 3.2 阶段二共性平台增强

| 方向 | 开发说明 |
| --- | --- |
| 运行监控 | 管理员视角处理取消、重试、恢复、失败排查和补偿；所有介入动作必须进入审计证据链。 |
| 高风险审批 | 高风险 MCP、外部交付、敏感文件下载和凭证相关动作进入统一运行安全策略，支持审批、二次确认和额度限制。 |
| 复杂分支与合并 | 在现有阶段积木和智能体集群基础上增强条件分支、合并策略和可视化调试，不回到自由画布心智。 |
| 文档生成增强 | Word 复杂模板、图片、目录、页眉页脚和大文档迁移到 Worker；PDF 作为具体客户版式需求进入交付适配。 |
| 观测与成本 | 完善模型 Token 统计、MCP 调用耗时、运行失败原因、租户资源用量和平台告警。 |
| 安全与会话 | 补登录设备管理、全端下线、密钥轮换方案、SSO 增强和更严格的浏览器端 Token 防护。 |

### 3.3 个性化集成阶段

| 类型 | 开发说明 |
| --- | --- |
| OA | 按客户 OA 的接口、流程编码、表单字段、审批策略和回调规范单独适配，不做阶段一通用内置能力假设。 |
| PDF | 按客户正式文档模板、签章、分页、目录、字体和归档要求设计；优先复用 Word/文档 Worker 能力。 |
| IM | 按飞书、钉钉、企微或客户私有 IM 的机器人权限、消息卡片、回调和租户安全策略适配。 |
| 数据库写入 | 只在明确业务系统边界、字段映射、幂等和回滚策略后接入，默认走高风险交付审批。 |
| 专属 MCP | 客户私有数据源、文件系统、OA、CRM、ERP 等连接器放在 `capabilities/mcp-servers/`，通过系统管理登记和租户能力池分配后使用。 |

## 4. 功能放置建议

| 内容 | 放置位置 | 原因 |
| --- | --- | --- |
| 底层模型供应商 | 系统管理 | 平台级配置，再放入租户可用模型范围 |
| 全局 MCP 注册 | 系统管理 | 连接能力和凭证策略属于平台治理 |
| 自研 MCP Server 源码 | `capabilities/mcp-servers/` | 需要独立开发、测试、启动和部署，再由系统管理登记 |
| MCP 在某租户内可用 | 系统管理 + 租户管理 | 系统管理在租户抽屉放入可用能力池，租户管理再分配给用户 / 部门 / 角色；分配后前端可见即能用 |
| Skills 上架 | 系统管理或能力资产 | 通用 Skills 可平台上架，业务 Skills 可租户内创建 |
| Skill 源码 | `capabilities/skills/` | 存放产品运行时 Skill 的说明、约束、样例和测试，发布后形成资产记录 |
| 智能体模板 | 能力资产 | 是流程设计的核心复用单元 |
| 提示词模板 | 能力资产 | 被智能体模板或节点引用，需版本化 |
| 交付能力 | 能力资产 + 系统管理 | 能力定义在资产，底层通道登记测试和单租户配置在系统管理 |
| 用户、部门、角色 | 租户管理 | 当前租户内管理，决定谁进入哪些模块、资源范围和能力池 |
| 资源范围 | 租户管理 | 控制当前租户内角色、人员、部门可见的流程、资产、运行记录和交付物 |
| 我的待办 | 业务工作台 | 表示当前用户需要处理的暂停点 |
| 业务运行详情 | 业务工作台 | 承接暂停恢复、追问确认、人工审核和交付确认 |
| 运行态摘要 | 业务工作台 | 表示有权限查看的运行状态，不一定需要处理 |
| 运行监控 | 后续管理入口 | 管理员处理取消、重试、补偿等运行介入动作 |
| 运行审计 | 运行审计 | 只读查看执行链路、节点快照、工具调用和交付证据 |
| 流程模板发起 | 业务工作台 | 点击后创建运行实例，进入交互式步骤，不进入设计态编排 |

## 5. 权限分层要点

- 系统管理员和租户管理员路由分层。
- JWT + 租户上下文 + 角色校验。
- 租户、用户、角色、部门的基础表设计。
- 系统配置、模型配置、租户可用能力池的治理思路。
- 数据库迁移脚本随代码版本提交。
- Agentum 第一阶段将租户管理员能力落到“租户管理”入口，仍保留平台级系统管理与租户内管理的权限边界。

## 6. 验证记录

本节记录每轮完成后的验证结果。

| 日期 | 验证 | 结果 |
| --- | --- | --- |
| 2026-05-05 | `pnpm build:web` | 通过 |
| 2026-05-05 | `pnpm lint:web` | 通过 |
| 2026-05-05 | JSON Schema 解析检查 | 通过 |
| 2026-05-05 | `./gradlew test` / `gradle test` | 未执行：当前仓库没有 Gradle Wrapper，本机也未安装 `gradle` |
| 2026-05-06 | `git diff --check` | 通过 |
| 2026-05-07 | `pnpm lint:web` | 通过 |
| 2026-05-07 | `pnpm build:web` | 通过 |
| 2026-05-08 | `docker run --rm -v "$PWD":/workspace -w /workspace gradle:8.10.2-jdk21 gradle :apps:api:test --no-daemon` | 通过 |
| 2026-05-08 | OpenAPI YAML 解析检查 | 通过 |
| 2026-05-08 | `git diff --check` | 通过 |
| 2026-05-08 | `pnpm lint:web` | 通过 |
| 2026-05-08 | `pnpm build:web` | 通过 |
| 2026-05-08 | `pnpm lint:web` | 通过：租户管理页接组织概览 API 后复验 |
| 2026-05-08 | `pnpm build:web` | 通过：租户管理页接组织概览 API 后复验 |
| 2026-05-08 | `make dev-infra` | 未通过：本机 Docker daemon 未启动，无法进行运行态前后端联调 |
| 2026-05-08 | `gradle :apps:api:test --no-daemon` | 未执行：本机尚未安装 `gradle` |
| 2026-05-08 | `docker run --rm -v "$PWD":/workspace -w /workspace gradle:8.10.2-jdk21 gradle :apps:api:test --no-daemon` | 未执行：本机 Docker daemon 未启动 |
| 2026-05-08 | `pnpm lint:web` | 通过：新增成员弹窗接入后复验 |
| 2026-05-08 | `pnpm build:web` | 通过：新增成员弹窗接入后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-08 | OpenAPI YAML 解析检查 | 通过：新增成员接口补入契约后复验 |
| 2026-05-08 | `git diff --check` | 通过：新增成员接口和弹窗后复验 |
| 2026-05-08 | `gradle :apps:api:test --no-daemon` | 未执行：本机尚未安装 `gradle` |
| 2026-05-08 | `docker run --rm -v "$PWD":/workspace -w /workspace gradle:8.10.2-jdk21 gradle :apps:api:test --no-daemon` | 未执行：本机 Docker daemon 未启动 |
| 2026-05-08 | `pnpm lint:web` | 通过：新增部门弹窗接入后复验 |
| 2026-05-08 | `pnpm build:web` | 通过：新增部门弹窗接入后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-08 | OpenAPI YAML 解析检查 | 通过：新增部门接口补入契约后复验 |
| 2026-05-08 | `git diff --check` | 通过：新增部门接口和弹窗后复验 |
| 2026-05-08 | `pnpm lint:web` | 通过：注释与日志治理后复验 |
| 2026-05-08 | `pnpm build:web` | 通过：注释与日志治理后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-08 | `git diff --check` | 通过：注释与日志治理后复验 |
| 2026-05-08 | `./gradlew test` / `gradle test` / Docker Gradle 镜像 | 未执行成功：仓库缺少 Gradle wrapper，本机未安装 Gradle，Docker daemon 未启动 |
| 2026-05-08 | `pnpm lint:web` | 通过：补齐登录响应 DTO 后复验 |
| 2026-05-08 | `pnpm build:web` | 通过：补齐登录响应 DTO 后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-08 | `git diff --check` | 通过：补齐登录响应 DTO 后复验 |
| 2026-05-08 | `pnpm lint:web` | 通过：全代码注释补充后复验 |
| 2026-05-08 | `pnpm build:web` | 通过：全代码注释补充后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-08 | `git diff --check` | 通过：全代码注释补充后复验 |
| 2026-05-08 | `gradle :apps:api:test --no-daemon` / Docker Gradle 镜像 | 未执行成功：本机未安装 Gradle，Docker daemon 未启动 |
| 2026-05-09 | `pnpm --filter @agentum/web lint` | 通过：登录页表单切换为 Ant Design 组件、错误提示改为 `message` 后复验 |
| 2026-05-09 | `pnpm --filter @agentum/web build` | 通过：登录页表单切换为 Ant Design 组件后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-09 | `./gradlew :apps:api:test --no-daemon` | 通过：仓库已具备 Gradle Wrapper，本地 API 单元测试可直接通过 wrapper 执行 |
| 2026-05-09 | `git diff --check` | 通过：登录页样式和验证记录更新后复验 |
| 2026-05-09 | `git diff --check` | 通过：租户管理定位、权限分层和页面改造计划文档更新后复验 |
| 2026-05-09 | `pnpm lint:web` | 通过：空间管理改为租户管理、租户管理页签化后复验 |
| 2026-05-09 | `pnpm build:web` | 通过：空间管理改为租户管理、租户管理页签化后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-09 | `./gradlew :apps:api:test --no-daemon` | 通过：登录入口角色改为 `tenant_admin` 后复验 |
| 2026-05-09 | OpenAPI YAML 解析检查 | 通过：登录入口枚举和租户管理接口描述更新后复验 |
| 2026-05-09 | `git diff --check` | 通过：租户管理命名和权限设计更新后复验 |
| 2026-05-09 | `./gradlew :apps:api:bootRun --args='--server.port=18080'` | 通过启动检查：Flyway 3 个迁移校验通过，本地库已到 `202605090001`；检查后手动停止进程 |
| 2026-05-09 | API smoke | 通过：`/actuator/health` 为 UP，公开租户列表 3 条，`tenantadmin / agentum123` 以 `tenant_admin` 登录成功 |
| 2026-05-09 | `./gradlew :apps:api:test --no-daemon` | 通过：含 `SystemAdminAccessTest` |
| 2026-05-09 | `pnpm --filter @agentum/web lint` / `build` | 通过：系统管理页接入 API 后复验 |
| 2026-05-09 | `./gradlew :apps:api:test --no-daemon` | 通过：租户成员关系角色/部门调整接口后复验 |
| 2026-05-09 | `pnpm --filter @agentum/web lint` / `build` | 通过：系统管理深色可读性与租户管理风格统一后复验 |
| 2026-05-09 | 文档一致性检查（`development-standards` / `system-overview` / `architecture` / `progress`） | 通过：新增“系统管理母版化设计”与“分页组件与契约统一”规范 |
| 2026-05-11 | `./gradlew :apps:api:test --no-daemon` | 通过：租户内自定义角色分页与写接口后复验 |
| 2026-05-11 | `pnpm --filter @agentum/web lint` / `build` | 通过：租户管理角色权限页接入 API、统一系统管理按钮与弹窗后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-11 | OpenAPI YAML 解析检查 | 通过：租户内自定义角色接口补入契约后复验 |
| 2026-05-11 | `git diff --check` | 通过：租户角色权限与界面统一后复验 |
| 2026-05-13 | `pnpm lint:web` | 通过 |
| 2026-05-13 | `pnpm build:web` | 通过，仍有 Ant Design vendor chunk 超 500 kB 提示 |
| 2026-05-13 | `./gradlew test` | 通过 |
| 2026-05-13 | OpenAPI YAML 解析检查 | 通过：使用 Ruby YAML 解析；本机 Python 环境缺少 PyYAML |
| 2026-05-13 | `git diff --check` | 通过 |
| 2026-05-14 | `pnpm --filter @agentum/web lint` | 通过：管理台分页组件和深色模式样式后复验 |
| 2026-05-14 | `pnpm --filter @agentum/web build` | 通过：仍有 Ant Design vendor chunk 超 500 kB 提示 |
| 2026-05-14 | `./gradlew :apps:api:test --no-daemon` | 通过：统一分页组件、系统管理分页接口和既有权限测试后复验 |
| 2026-05-14 | OpenAPI YAML 解析检查 | 通过：系统管理分页响应模型补入契约后复验 |
| 2026-05-19 | `pnpm --filter @agentum/web lint` | 通过：系统管理页模型/能力编辑与概览优化后复验 |
| 2026-05-19 | `pnpm --filter @agentum/web build` | 通过：系统管理页模型/能力编辑与概览优化后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-19 | `./gradlew :apps:api:test --no-daemon` | 通过：系统管理模型供应商、能力编辑和租户模型分配状态接口后复验 |
| 2026-05-19 | OpenAPI YAML 解析检查 | 通过：系统管理编辑接口与模型 API Key 字段补入契约后复验 |
| 2026-05-19 | `pnpm --filter @agentum/web lint` | 通过：租户管理分配卡片、前端动作/编码隐藏后复验 |
| 2026-05-19 | `pnpm --filter @agentum/web build` | 通过：租户管理分配卡片后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-19 | `./gradlew :apps:api:test --no-daemon` | 通过：租户页签/能力分配卡片接口和 `grant_group_id` 数据模型后复验 |
| 2026-05-19 | OpenAPI YAML 解析检查 | 通过：租户分配卡片接口契约更新后复验 |
| 2026-05-19 | `git diff --check` | 通过：租户分配卡片前后端与文档更新后复验 |
| 2026-05-19 | `pnpm --filter @agentum/web lint` | 通过：分配卡片名称字段和卡片双行信息展示后复验 |
| 2026-05-19 | `pnpm --filter @agentum/web build` | 通过：分配卡片名称字段和卡片双行信息展示后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-19 | `./gradlew :apps:api:test --no-daemon` | 通过：页签/能力分配卡片名称落库与响应契约后复验 |
| 2026-05-19 | OpenAPI YAML 解析检查 | 通过：分配卡片名称字段补入契约后复验 |
| 2026-05-14 | `git diff --check` | 通过：统一分页实现后复验 |
| 2026-05-25 | `pnpm lint:web && pnpm build:web` | 通过：流程设计模板后端化、能力资产引用和页面视觉优化后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-25 | `./gradlew :apps:api:test --tests 'com.agentum.workflow.application.*'` | 通过：工作流设计目录服务、草稿保存和发布相关测试复验 |
| 2026-05-25 | `curl -I http://localhost:5173/` | 通过：本地前端开发服务返回 200 |
| 2026-05-25 | `git diff --check` | 通过：流程设计后端化与进度文档更新后复验 |
| 2026-05-26 | `./gradlew test` | 通过：修复工作流草稿保存时 Hibernate ActionQueue 导致唯一约束冲突，并修复单元测试中的 Mockito 桩方法与签名冲突 |
| 2026-05-26 | `pnpm --filter @agentum/web build` | 通过：验证前端构建无类型错误 |
| 2026-05-28 | `pnpm --filter @agentum/web lint` | 通过：业务工作台接入 `/workbench/*` 真实数据并移除模拟待办、运行记录和流程模板 |
| 2026-05-28 | `pnpm --filter @agentum/web build` | 通过：业务工作台后端化后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-05-28 | `./gradlew :apps:api:test --no-daemon --rerun-tasks` | 通过：新增 `WorkbenchAccess` / `WorkbenchService` 单元测试 + 既有套件全部通过 |
| 2026-05-28 | OpenAPI YAML 解析检查 | 通过：业务工作台路径与 `WorkbenchSummary` / `WorkbenchAvailableWorkflowPageResponse` 等 Schema 补入契约后复验 |
| 2026-05-28 | `git diff --check` | 通过：业务工作台后端化、契约和文档同步后复验 |
| 2026-06-01 | `pnpm lint:web` | 通过：业务工作台任务处理预览（当前处理分节点、执行链路、固定页头布局、侧栏 Logo 收起居中）后复验 |
| 2026-06-05 | `./gradlew :apps:api:test` | 通过：业务工作台运行态表、运行实例创建、待办推进、全部流程可见与权限区分后端测试后复验 |
| 2026-06-05 | `pnpm --dir apps/web exec tsc --noEmit` | 通过：业务工作台任务中心、运行详情和待办动作接入后端类型后复验 |
| 2026-06-05 | `pnpm lint:web` | 通过：业务工作台移除本地任务预览并接入运行态 API 后复验 |
| 2026-06-05 | `pnpm build:web` | 通过：业务工作台运行态前端构建后复验；Vite 提示 Ant Design vendor chunk 超过 500 kB |
| 2026-06-05 | OpenAPI YAML 解析检查 | 通过：业务工作台运行实例、任务记录、待办完成接口契约后复验 |
| 2026-06-05 | `git diff --check` | 通过：业务工作台运行态全链路更新后复验 |
| 2026-06-05 | `./gradlew :apps:api:test` | 通过：真实运行执行器、模型/MCP/交付日志、变量快照和节点失败留痕后端复验 |
| 2026-06-05 | `pnpm lint:web` | 通过：业务工作台移除未接入动作、待办/任务记录口径和失败态展示后复验 |
| 2026-06-05 | `pnpm build:web` | 通过：真实运行态前端构建复验；Vite 仍提示 Ant Design vendor chunk 超过 500 kB |
| 2026-06-05 | `git diff --check` | 通过：真实运行态、前端交互和文档更新空白检查 |
| 2026-06-22 | `./gradlew test` | 通过：Word 数字字体拆分、表格首行/框线/独立行距及系统配置清洗全套后端测试 |
| 2026-06-22 | `pnpm lint:web` / `pnpm build:web` | 通过：流程 Word 交付节点与系统管理初始化配置前端复验；Vite 仍提示 Ant Design vendor chunk 超过 500 kB |
| 2026-06-22 | OpenAPI YAML 解析 / `git diff --check` | 通过：Word 交付样式契约、初始化迁移和文档同步复验 |
| 2026-06-26 | `git diff --check` | 通过：阶段一完成口径、个性化集成边界和后续开发说明文档更新后复验 |
| 2026-06-26 | `git diff --check` | 通过：README 产品定位、亮点和代码实现对应关系重写后复验 |
