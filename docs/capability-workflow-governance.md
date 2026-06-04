# 能力—流程—权限勾稽治理规范

更新时间：2026-06-04

本文档梳理 Agentum 阶段一中**系统能力、租户能力、流程版本、协作权限**之间的边界、当前实现与后续优化选型。长期产品说明见 [系统介绍](./system-overview.md)，模块边界见 [架构文档](./architecture.md)。

## 1. 治理目标

最终用户接触的是**流程运行实例**，但运行实例依赖三层不可混用的资产：

```text
系统能力（system_capabilities）
  -> 租户能力池（tenant_capability_grants）
    -> 租户内分配（resource_grants：用户/部门/角色）
      -> 用户自建能力（tenant_asset_capabilities）
        -> 流程设计（workflow_definitions + workflow_versions）
          -> 业务工作台发起（available-workflows）
```

设计原则：

1. **发布即冻结**：对外可用的必须是不可变版本，设计态可以继续演进。
2. **权限不向下传递**：流程编辑权限 ≠ 节点引用能力权限；保存/发布时按**当前操作者**重新校验。
3. **列表语义分离**：「我的 / 协作开放 / 对我开放」互不重复展示同一资源。
4. **收回 ≠ 删除**：收回业务入口保留历史版本；删除是治理级破坏性操作，需引用检查。

## 2. 三类能力来源

| 层级 | 数据表 / API | 业务可见入口 | 生命周期 |
|------|-------------|-------------|---------|
| 系统能力 | `system_capabilities` | 能力资产 · 对我开放（需租户分配） | 系统管理登记 → 放入租户池 → 租户管理分配 |
| 租户分配 | `resource_grants` | 能力资产 · 对我开放 | 分配/收回分配；关闭后业务侧不可见，系统登记仍在 |
| 用户自建 | `tenant_asset_capabilities` | 能力资产 · 我的能力；被共享时在「对我开放」 | 草稿 ↔ 已发布；发布前必须改回草稿才能编辑内容 |

### 2.1 租户分配能力「关闭」后怎么处理（当前 + 目标）

**当前实现**

- 系统管理可将能力放入租户池（`tenant_capability_grants`，`enabled/disabled`）。
- 租户管理通过 `resource_grants` 分配给用户/部门/角色。
- 业务侧「对我开放」只展示**已分配且租户池仍 enabled** 的系统能力（见 `AssetManagementService.filterVisibleCapabilities`）。

**关闭路径**

| 操作 | 影响范围 | 业务侧表现 | 设计态引用 |
|------|---------|-----------|-----------|
| 租户管理收回分配 | 指定主体 | 从「对我开放」消失 | 流程保存/发布时引用校验失败 |
| 系统管理关闭租户池 | 整租户 | 全部业务用户不可见 | 同上 |
| 系统能力改回草稿/停用 | 全局登记 | 不可再分配 | 已发布流程节点引用应阻断（待引用索引） |

**目标（阶段二）**

- 建立 `capability_reference_index`：记录流程版本、智能体模板、运行快照引用了哪一版能力。
- 关闭/收回前给出「影响面预览」：多少流程版本、多少运行实例。
- 默认策略：**禁止**在有活跃运行实例时关闭；允许「仅阻止新发起」类软下线。

### 2.2 用户自建能力 vs 流程版本

| 维度 | 用户自建能力 | 流程 |
|------|------------|------|
| 设计态 | 单表记录，发布即正式版 | `workflow_definitions` 工作副本 |
| 正式版 | 记录本身 status=published | `workflow_versions` 冻结快照 |
| 再编辑 | 必须「改回草稿」 | 可直接改；保存积木后标记「有未发布改动」 |
| 业务入口 | 被引用为节点配置 | `launch_enabled` + 最新冻结版本 |

能力更严，是因为能力是被引用的**零件**；流程是**组合体**，已有独立版本表。

## 3. 流程版本模型（方案 C，已落地）

### 3.1 字段语义

| 字段 | 含义 |
|------|------|
| `workflow_definitions.status` | 设计态是否与最近发布一致：`published` = 一致，`draft` = 有未发布改动或从未发布 |
| `workflow_versions` | 不可变快照 v1、v2…；运行态只能引用此表 |
| `launch_enabled` | 业务工作台是否允许**新发起**；false = 已收回入口 |

### 3.2 用户可见状态

| UI 标签 | 条件 |
|---------|------|
| 未发布 | `latestVersionNumber = 0` |
| 已发布 vN | 有版本且 `!hasUnpublishedChanges` |
| 已发布 vN · 有未发布改动 | 有版本且 `status = draft` |
| 已发布 vN · 已收回 | 有版本且 `launch_enabled = false` |

### 3.3 关键操作

| 操作 | 效果 | 是否可逆 |
|------|------|---------|
| 发布 | 新增冻结版本，业务可发起（若入口开放） | 版本不可修改，只能发新版本 |
| 收回入口 | `launch_enabled=false` | 可「恢复入口」 |
| 删除流程 | 删除定义 + 全部版本 | 不可逆；运行态接入后需引用保护 |
| 改积木/说明 | 有版本时标记未发布改动 | 重新发布生成 vN+1 |

### 3.4 业务工作台查询

可发起列表条件（与 design status 解耦）：

```text
当前用户有读取权限
AND launch_enabled = true
AND EXISTS workflow_versions
```

避免设计者改积木后 `status=draft` 误将已发布版本从业务侧下线。

## 4. 协作权限（流程 & 能力）

| 资源 | 读取/使用 | 编辑内容 | 权限配置 & 删除 |
|------|----------|---------|----------------|
| 流程 | read_scope + grants | edit_scope + grants | 仅创建者 |
| 用户自建能力 | read_scope + grants | edit_scope + grants | 仅创建者 |

**列表规则**

- 流程「协作开放」、能力「对我开放」均**排除创建者本人**（避免与「我的」重复）。
- 流程编辑权限**不继承**节点引用的 MCP/Skill/模板权限；保存/发布按操作者校验（`WorkflowNodeConfigValidator` / `AssetManagementService.normalizeAssetConfig`）。

## 5. 引用勾稽（当前缺口与建设路线）

### 5.1 当前已实现的校验

- 流程发布/保存：节点 config 中 MCP/Skill/交付能力须在租户能力池且对操作者有效。
- 智能体模板保存/发布：Skill/MCP/提示词模板引用须已发布且可读。
- 流程发布：图结构、变量、能力引用校验。

### 5.2 尚未落地的引用索引（代码中已标注 TODO）

| 场景 | 现状 | 目标 |
|------|------|------|
| 删除用户能力 | 仅校验创建者 | 被流程版本/运行快照引用则禁止 |
| 能力改回草稿 | 允许 | 被已发布流程引用则禁止或警告 |
| 关闭系统/租户能力 | 分配层隐藏 | 影响面分析 + 阻断策略 |
| 删除流程 | 创建者可删 | 有运行实例则禁止 |

### 5.3 推荐引用索引模型（阶段二选型）

```text
capability_reference_index
  ref_type: workflow_version | agent_template | run_snapshot
  ref_id
  asset_kind: system_capability | tenant_asset
  asset_id
  asset_version (nullable)
  tenant_id
  created_at
```

写入时机：流程发布、能力发布、运行实例创建。  
读取时机：删除、改回草稿、关闭分配、收回入口前的治理预览。

## 6. 优化方案选型摘要

| 议题 | 方案 A（严统一） | 方案 B（松统一） | **方案 C（当前）** |
|------|----------------|----------------|-------------------|
| 流程已发布后编辑 | 必须先改回草稿 | 与能力相同 | 工作副本可改，版本冻结 |
| 能力已发布后编辑 | 改回草稿 | 原地改 + 升版 | **改回草稿** |
| 业务可见性 | status=published | 同左 | **launch_enabled + 有版本** |
| 下线已发布 | 删除/停用 | 收回版本 | **收回入口 + 保留版本** |
| 用户体验 | 规则一致 | 操作少 | **语义分层 + 明确文案** |

**结论**：阶段一采用方案 C；阶段二补引用索引后，再评估是否在能力侧引入「发布版本表」与流程对齐。

## 7. 代码锚点（维护时优先阅读）

| 领域 | 路径 |
|------|------|
| 能力草稿/发布/改回草稿 | `apps/api/.../asset/application/AssetManagementService.java` |
| 能力对我开放过滤 | 同上 `filterVisibleCapabilities` / `loadAccessibleTenantAssetsForUser` |
| 流程版本发布 | `apps/api/.../workflow/application/WorkflowDraftService.java` |
| 流程 launch 收回 | 同上 `recallLaunch` / `restoreLaunch` |
| 流程版本摘要 | `WorkflowDraftRow.latestVersionNumber` 等 |
| 业务可发起 | `apps/api/.../workbench/application/WorkbenchService.java` |
| 流程设计 UI | `apps/web/src/surfaces/designer/WorkflowDraftsPage.tsx` |
| 能力资产 UI | `apps/web/src/surfaces/assets/AssetsPage.tsx` |

## 8. 后续文档同步

- 产品行为变更时同步 [system-overview.md](./system-overview.md) 对应章节。
- 表结构变更时同步 [architecture.md](./architecture.md) 数据模型节。
- 阶段进度写入 [progress/README.md](./progress/README.md)。
