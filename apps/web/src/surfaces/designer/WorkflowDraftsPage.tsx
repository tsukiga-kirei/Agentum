import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  Bot,
  CheckCircle2,
  Clock3,
  FilePlus2,
  GitBranch,
  Layers3,
  ListChecks,
  PanelRightOpen,
  PackageCheck,
  Search,
  TextCursorInput,
  X,
} from "lucide-react";
import { Pagination, Segmented } from "antd";
import { AgentumApiError, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type {
  WorkflowDraftRow,
  WorkflowPublishValidationResult,
  WorkflowStatus,
} from "../../types/workflow-contract";
import { WorkflowEditorPage } from "./WorkflowEditorPage";

// 工作流草稿列表是设计态入口，不等同于运行实例；发布后需要生成不可变 WorkflowVersion。
export type WorkflowDraft = WorkflowDraftRow;

const statusMeta: Record<WorkflowStatus, { label: string; className: string }> = {
  draft: {
    label: "草稿",
    className: "sys-info-tag--warn",
  },
  published: {
    label: "已发布",
    className: "sys-info-tag--success",
  },
  review: {
    label: "待校验",
    className: "sys-info-tag--info",
  },
};

const workflowPaginationLocale = {
  items_per_page: "条/页",
  jump_to: "跳至",
  jump_to_confirm: "确定",
  page: "页",
  prev_page: "上一页",
  next_page: "下一页",
  prev_5: "向前 5 页",
  next_5: "向后 5 页",
  prev_3: "向前 3 页",
  next_3: "向后 3 页",
  page_size: "每页条数",
};

type WorkflowDesignerTab = "overview" | "definitions" | "validation";

function formatPaginationTotal(count: number, range: [number, number], pageSize: number): string {
  return count <= pageSize ? `共 ${count} 条` : `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`;
}

export function WorkflowDraftsPage() {
  // 草稿列表已接入工作流草稿 API；编辑态改为阶段积木编排，运行实例会在后续独立建模。
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [workflows, setWorkflows] = useState<WorkflowDraft[]>([]);
  const [searchValue, setSearchValue] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const [draftName, setDraftName] = useState("");
  const [draftDescription, setDraftDescription] = useState("");
  const [formError, setFormError] = useState("");
  const [editingWorkflow, setEditingWorkflow] = useState<WorkflowDraft | null>(null);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(8);
  const [total, setTotal] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [validatingWorkflowId, setValidatingWorkflowId] = useState("");
  const [validationModal, setValidationModal] = useState<{
    workflow: WorkflowDraft;
    result: WorkflowPublishValidationResult;
  } | null>(null);
  const [validationError, setValidationError] = useState("");
  const [publishingWorkflowId, setPublishingWorkflowId] = useState("");
  const [publishSuccess, setPublishSuccess] = useState("");
  const [activeTab, setActiveTab] = useState<WorkflowDesignerTab>("overview");

  const loadDrafts = useCallback(async (nextPage = 1, keyword = searchValue, nextPageSize = pageSize) => {
    if (!token || !user?.tenantId) {
      setLoadError("当前账号缺少租户上下文，无法加载工作流草稿");
      setWorkflows([]);
      return;
    }

    setLoading(true);
    setLoadError("");

    try {
      const result = await workflowApi.listDrafts(user.tenantId, token, nextPage, nextPageSize, keyword);
      setWorkflows(result.items);
      setPage(result.page);
      setPageSize(result.size);
      setTotal(result.total);
    } catch (error) {
      console.warn("[workflow] 工作流草稿加载失败", getWorkflowErrorContext(error, user.tenantId));
      setLoadError(error instanceof AgentumApiError ? error.message : "无法加载工作流草稿");
      setWorkflows([]);
    } finally {
      setLoading(false);
    }
  }, [pageSize, searchValue, token, user?.tenantId]);

  useEffect(() => {
    void loadDrafts(1);
  }, [loadDrafts]);

  const filteredWorkflows = useMemo(() => {
    return workflows;
  }, [workflows]);

  const draftCount = workflows.filter((workflow) => workflow.status === "draft").length;
  const publishedCount = workflows.filter((workflow) => workflow.status === "published").length;
  const reviewCount = workflows.filter((workflow) => workflow.status === "review").length;
  const moduleOptions = [
    {
      value: "overview",
      label: (
        <span className="login-portal-option">
          <GitBranch className="login-portal-option-icon" aria-hidden="true" />
          <span>总览</span>
        </span>
      ),
    },
    {
      value: "definitions",
      label: (
        <span className="login-portal-option">
          <PanelRightOpen className="login-portal-option-icon" aria-hidden="true" />
          <span>工作流定义</span>
        </span>
      ),
    },
    {
      value: "validation",
      label: (
        <span className="login-portal-option">
          <ListChecks className="login-portal-option-icon" aria-hidden="true" />
          <span>发布校验</span>
        </span>
      ),
    },
  ];

  async function handleCreateDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || !user?.tenantId) {
      setFormError("当前账号缺少租户上下文，无法保存草稿");
      return;
    }

    const name = draftName.trim();
    const description = draftDescription.trim();

    if (!name) {
      setFormError("请输入工作流名称");
      return;
    }

    setSubmitting(true);
    setFormError("");

    try {
      await workflowApi.createDraft(user.tenantId, token, { name, description });
      setDraftName("");
      setDraftDescription("");
      setIsCreating(false);
      await loadDrafts(1, searchValue);
    } catch (error) {
      console.warn("[workflow] 工作流草稿创建失败", getWorkflowErrorContext(error, user.tenantId, { name }));
      setFormError(error instanceof AgentumApiError ? error.message : "保存草稿失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleValidateForPublish(workflow: WorkflowDraft) {
    if (!token || !user?.tenantId) {
      setValidationError("当前账号缺少租户上下文，无法执行发布校验");
      return;
    }

    setValidatingWorkflowId(workflow.id);
    setValidationError("");
    setPublishSuccess("");

    try {
      const result = await workflowApi.validateForPublish(user.tenantId, workflow.id, token);
      setValidationModal({ workflow, result });
    } catch (error) {
      console.warn("[workflow] 工作流发布校验失败", getWorkflowErrorContext(error, user.tenantId, { workflowId: workflow.id }));
      setValidationError(error instanceof AgentumApiError ? error.message : "发布校验失败，请稍后重试");
    } finally {
      setValidatingWorkflowId("");
    }
  }

  async function handlePublish(workflow: WorkflowDraft) {
    if (!token || !user?.tenantId) {
      setValidationError("当前账号缺少租户上下文，无法正式发布");
      return;
    }

    setPublishingWorkflowId(workflow.id);
    setValidationError("");

    try {
      const result = await workflowApi.publish(user.tenantId, workflow.id, token);
      setWorkflows((currentWorkflows) => currentWorkflows.map((item) => item.id === workflow.id ? result.draft : item));
      setValidationModal(null);
      setPublishSuccess(`“${result.draft.name}”已发布为 v${result.versionNumber}`);
    } catch (error) {
      console.warn("[workflow] 工作流正式发布失败", getWorkflowErrorContext(error, user.tenantId, { workflowId: workflow.id }));
      setValidationError(error instanceof AgentumApiError ? error.message : "正式发布失败，请稍后重试");
    } finally {
      setPublishingWorkflowId("");
    }
  }

  if (editingWorkflow) {
    return (
      <WorkflowEditorPage
        workflow={editingWorkflow}
        onBack={() => setEditingWorkflow(null)}
        onDraftSaved={(draft) => {
          setEditingWorkflow(draft);
          setWorkflows((currentWorkflows) => currentWorkflows.map((item) => item.id === draft.id ? draft : item));
        }}
      />
    );
  }

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
      <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
        <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex min-w-0 gap-4">
            <div className="workflow-design-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
              <GitBranch className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">流程设计</h1>
                <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                  流程治理
                </span>
              </div>
              <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                管理工作流定义、发布状态和阶段积木配置；已发布版本会冻结为不可变快照，后续修改在草稿中继续演进。
              </p>
            </div>
          </div>
          <button type="button" onClick={() => setIsCreating(true)} className="sys-btn sys-btn--primary">
            <FilePlus2 size={15} aria-hidden="true" />
            新建工作流
          </button>
        </header>

        <div className="system-mgmt-module-switch mb-5">
          <div className="system-mgmt-segmented-scroll">
            <Segmented
              aria-label="流程设计模块"
              value={activeTab}
              options={moduleOptions}
              onChange={(value) => setActiveTab(value as WorkflowDesignerTab)}
              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
            />
          </div>
          <div className="login-portal-description login-portal-description--business">
            <span className="login-portal-description-dot" />
            {activeTab === "overview" ? "流程设计总览与积木说明" : activeTab === "definitions" ? "工作流定义卡片与积木配置入口" : "发布前校验、阻塞项与正式发布"}
          </div>
        </div>

        <section className="sys-overview-stats mb-5" aria-label="工作流概览">
          <OverviewStat icon={GitBranch} label="全部工作流" value={String(total)} tone="primary" />
          <OverviewStat icon={Clock3} label="当前页草稿" value={String(draftCount)} tone="info" />
          <OverviewStat icon={CheckCircle2} label="当前页已发布" value={String(publishedCount)} tone="success" />
          <OverviewStat icon={ListChecks} label="当前页待校验" value={String(reviewCount)} tone="cap" />
        </section>

        {activeTab === "overview" ? (
          <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]" aria-label="流程设计总览">
            <div className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-5 shadow-[var(--shadow-sm)]">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-base font-semibold text-[var(--color-text-primary)]">设计策略</h2>
                  <p className="agent-muted mt-2 max-w-3xl text-sm leading-6">
                    流程设计页不再用自由画布表达复杂编排，而是通过少量积木搭出清晰步骤。左侧配置工作流顺序，右侧配置每个积木的输入、能力、输出参数和用户交互模式。
                  </p>
                </div>
                <button type="button" onClick={() => setActiveTab("definitions")} className="sys-btn sys-btn--primary sys-btn--sm">
                  <PanelRightOpen size={14} aria-hidden="true" />
                  进入定义
                </button>
              </div>
              <div className="mt-5 grid gap-3 md:grid-cols-2">
                <BrickIntroCard icon={TextCursorInput} title="输入节点" description="配置用户需要填写的输入框，当前先支持基础文本输入。" />
                <BrickIntroCard icon={Bot} title="单智能体节点" description="选择能力中的智能体，或配置提示词模板、MCP、Skill 和输出参数。" />
                <BrickIntroCard icon={Layers3} title="智能体集群节点" description="组合多个单智能体，并配置并行执行、拼接规则和聚合输出。" />
                <BrickIntroCard icon={PackageCheck} title="交付节点" description="配置最终文档、邮件、OA 或 Webhook 等交付能力。" />
              </div>
            </div>
            <div className="space-y-4">
              <section className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-5 shadow-[var(--shadow-sm)]">
                <h2 className="text-base font-semibold text-[var(--color-text-primary)]">最近工作流</h2>
                <div className="mt-4 space-y-2">
                  {filteredWorkflows.slice(0, 4).map((workflow) => {
                    const status = statusMeta[workflow.status];
                    return (
                      <button key={workflow.id} type="button" onClick={() => setEditingWorkflow(workflow)} className="flex w-full items-center justify-between gap-3 rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] px-3 py-2 text-left">
                        <span className="min-w-0">
                          <span className="block truncate text-sm font-medium text-[var(--color-text-primary)]">{workflow.name}</span>
                          <span className="mt-1 block text-xs text-[var(--color-text-tertiary)]">{workflow.nodeCount} 个积木 · {workflow.pausePointCount} 个暂停点</span>
                        </span>
                        <span className={`sys-info-tag ${status.className}`}>{status.label}</span>
                      </button>
                    );
                  })}
                  {!loading && filteredWorkflows.length === 0 ? <p className="text-sm text-[var(--color-text-tertiary)]">暂无工作流定义。</p> : null}
                </div>
              </section>
              <section className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-5 shadow-[var(--shadow-sm)]">
                <h2 className="text-base font-semibold text-[var(--color-text-primary)]">发布边界</h2>
                <p className="agent-muted mt-2 text-sm leading-6">
                  发布前会校验步骤顺序、输入输出参数、能力引用和交付配置。已发布版本冻结为不可变快照，运行态不会被草稿修改影响。
                </p>
              </section>
            </div>
          </section>
        ) : null}

        {activeTab === "definitions" ? (
          <section className="overflow-hidden rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]" aria-labelledby="workflow-list-title">
            <div className="p-5">
              <div className="workflow-definition-toolbar">
                <div>
                  <h2 id="workflow-list-title">工作流定义</h2>
                  <p>卡片点击后进入具体积木配置，支持左侧搭步骤、右侧配置能力。</p>
                </div>
                <div className="workflow-definition-toolbar-actions">
                  <label className="workflow-definition-search">
                    <Search className="h-4 w-4" aria-hidden="true" />
                    <span className="sr-only">搜索工作流</span>
                    <input
                      value={searchValue}
                      onChange={(event) => {
                        setSearchValue(event.target.value);
                        setPage(1);
                      }}
                      placeholder="搜索名称或说明"
                    />
                  </label>
                </div>
              </div>

              {loadError ? <div className="workflow-feedback workflow-feedback--danger">{loadError}</div> : null}
              {validationError ? <div className="workflow-feedback workflow-feedback--danger">{validationError}</div> : null}
              {publishSuccess ? <div className="workflow-feedback workflow-feedback--success">{publishSuccess}</div> : null}

              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                {filteredWorkflows.map((workflow) => (
                  <WorkflowDefinitionCard
                    key={workflow.id}
                    workflow={workflow}
                    validating={validatingWorkflowId === workflow.id}
                    onOpen={() => setEditingWorkflow(workflow)}
                    onValidate={() => void handleValidateForPublish(workflow)}
                  />
                ))}
              </div>

              {loading ? (
                <div className="workflow-definition-empty-state">
                  <Clock3 className="h-8 w-8" aria-hidden="true" />
                  <p>正在加载工作流</p>
                </div>
              ) : null}

              {!loading && filteredWorkflows.length === 0 ? (
                <div className="workflow-definition-empty-state">
                  <AlertCircle className="h-8 w-8" aria-hidden="true" />
                  <p>没有找到匹配的工作流</p>
                  <span>可以调整搜索词，或创建一个新的工作流。</span>
                </div>
              ) : null}

              {total > 0 ? (
                <div className="agent-admin-pagination-wrap px-4 py-4">
                  <Pagination
                    className="agent-admin-pagination"
                    current={page}
                    pageSize={pageSize}
                    total={total}
                    locale={workflowPaginationLocale}
                    showSizeChanger={{ className: "agent-admin-select", popupClassName: "agent-select-dropdown agent-admin-select-dropdown" }}
                    pageSizeOptions={["8", "16", "32"]}
                    showTotal={(count, range) => formatPaginationTotal(count, range, pageSize)}
                    disabled={loading}
                    onChange={(nextPage, nextPageSize) => void loadDrafts(nextPage, searchValue, nextPageSize)}
                    onShowSizeChange={(nextPage, nextPageSize) => void loadDrafts(nextPage, searchValue, nextPageSize)}
                  />
                </div>
              ) : null}
            </div>
          </section>
        ) : null}

        {activeTab === "validation" ? (
          <section className="overflow-hidden rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]" aria-labelledby="workflow-validation-list-title">
            <div className="p-5">
              <div className="sys-hint mb-4">
                <ListChecks size={14} />
                发布校验会重新检查步骤结构、输入输出参数和变量依赖；正式发布仍由后端再次复核。
              </div>
              <div className="workflow-definition-toolbar">
                <div>
                  <h2 id="workflow-validation-list-title">发布校验</h2>
                  <p>对草稿执行校验，校验通过后冻结为正式版本。</p>
                </div>
              </div>
              {validationError ? <div className="workflow-feedback workflow-feedback--danger">{validationError}</div> : null}
              {publishSuccess ? <div className="workflow-feedback workflow-feedback--success">{publishSuccess}</div> : null}
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                {filteredWorkflows.map((workflow) => (
                  <WorkflowValidationCard
                    key={workflow.id}
                    workflow={workflow}
                    validating={validatingWorkflowId === workflow.id}
                    onValidate={() => void handleValidateForPublish(workflow)}
                    onOpen={() => setEditingWorkflow(workflow)}
                  />
                ))}
              </div>
              {!loading && filteredWorkflows.length === 0 ? (
                <div className="workflow-definition-empty-state">
                  <AlertCircle className="h-8 w-8" aria-hidden="true" />
                  <p>暂无可校验的工作流</p>
                </div>
              ) : null}
            </div>
          </section>
        ) : null}
      </div>

      {isCreating ? (
        <div className="sys-modal-mask" onClick={() => setIsCreating(false)}>
          <section className="sys-modal" style={{ maxWidth: 560 }} aria-labelledby="create-draft-title" onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <span id="create-draft-title" className="sys-modal-title">新建工作流</span>
              <button className="sys-modal-close" onClick={() => setIsCreating(false)} aria-label="关闭新建工作流弹窗"><X size={18} /></button>
            </div>
            <form onSubmit={handleCreateDraft}>
              <div className="sys-modal-body">
                <div className="sys-hint mb-4">先保存基础信息，再进入阶段积木配置变量、能力和发布规则。</div>
                <label className="sys-field">
                  <span className="sys-field-label sys-field-label--required">工作流名称</span>
                  <div className="sys-field-input-wrap">
                    <GitBranch size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input value={draftName} onChange={(event) => { setDraftName(event.target.value); setFormError(""); }} className="sys-field-input" placeholder="例如：客户续约风险评估流程" />
                  </div>
                </label>
                <label className="sys-field">
                  <span className="sys-field-label">说明</span>
                  <textarea value={draftDescription} onChange={(event) => setDraftDescription(event.target.value)} className="sys-field-textarea" placeholder="描述流程适用场景、输入材料和最终交付物" />
                </label>
                {formError ? <p className="workflow-feedback workflow-feedback--danger">{formError}</p> : null}
              </div>
              <div className="sys-modal-footer">
                <button type="button" onClick={() => { setIsCreating(false); setFormError(""); }} className="sys-btn sys-btn--default">取消</button>
                <button type="submit" disabled={submitting} className="sys-btn sys-btn--primary">
                  <FilePlus2 size={14} aria-hidden="true" />
                  {submitting ? "保存中" : "保存草稿"}
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}

      {validationModal ? (
        <div className="sys-modal-mask" onClick={() => setValidationModal(null)}>
          <section className="sys-modal" style={{ maxWidth: 720 }} aria-labelledby="publish-validation-title" onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <div>
                <div className="sys-field-label" style={{ marginBottom: 4 }}>发布校验</div>
                <span id="publish-validation-title" className="sys-modal-title">{validationModal.workflow.name}</span>
              </div>
              <button className="sys-modal-close" onClick={() => setValidationModal(null)} aria-label="关闭发布校验结果"><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
              <div className={`workflow-validation-summary ${validationModal.result.valid ? "workflow-validation-summary--success" : "workflow-validation-summary--warning"}`}>
                <p>{validationModal.result.valid ? "当前草稿已通过发布校验" : "当前草稿还不能进入发布流程"}</p>
                <span>
                  节点 {validationModal.result.nodeCount} 个，连线 {validationModal.result.edgeCount} 条，
                  {validationModal.result.issues.length === 0 ? "未发现阻塞项。" : `发现 ${validationModal.result.issues.length} 个阻塞项。`}
                </span>
              </div>
              {validationModal.result.issues.length > 0 ? (
                <div className="workflow-validation-issues">
                  {validationModal.result.issues.map((issue) => (
                    <article key={`${issue.code}-${issue.nodeId}-${issue.message}`}>
                      <div><span className="sys-info-tag sys-info-tag--danger">阻塞</span><span>{issue.code}</span></div>
                      <p>{issue.message}</p>
                      {issue.nodeName ? <small>关联节点：{issue.nodeName}</small> : null}
                    </article>
                  ))}
                </div>
              ) : (
                <div className="sys-hint">正式发布会再次执行后端校验，并冻结当前节点、连线和变量声明为不可变版本。</div>
              )}
            </div>
            <div className="sys-modal-footer">
              <button type="button" onClick={() => setValidationModal(null)} className="sys-btn sys-btn--default">关闭</button>
              {validationModal.result.valid ? (
                <button type="button" disabled={publishingWorkflowId === validationModal.workflow.id} onClick={() => void handlePublish(validationModal.workflow)} className="sys-btn sys-btn--primary">
                  <CheckCircle2 size={14} aria-hidden="true" />
                  {publishingWorkflowId === validationModal.workflow.id ? "发布中" : "正式发布"}
                </button>
              ) : null}
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function BrickIntroCard({ icon: Icon, title, description }: { icon: typeof GitBranch; title: string; description: string }) {
  return (
    <article className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
      <div className="flex items-start gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-[var(--color-bg-card)] text-[var(--color-primary)] ring-1 ring-[var(--color-border-light)]">
          <Icon size={18} aria-hidden="true" />
        </span>
        <div>
          <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">{title}</h3>
          <p className="mt-1 text-sm leading-6 text-[var(--color-text-secondary)]">{description}</p>
        </div>
      </div>
    </article>
  );
}

function WorkflowDefinitionCard({
  workflow,
  validating,
  onOpen,
  onValidate,
}: {
  workflow: WorkflowDraft;
  validating: boolean;
  onOpen: () => void;
  onValidate: () => void;
}) {
  const status = statusMeta[workflow.status];

  return (
    <article className="flex min-h-[210px] flex-col rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 shadow-[var(--shadow-xs)] transition hover:border-[var(--color-primary)]">
      <button type="button" onClick={onOpen} className="flex-1 text-left">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="truncate text-base font-semibold text-[var(--color-text-primary)]">{workflow.name}</h3>
            <p className="mt-2 line-clamp-2 text-sm leading-6 text-[var(--color-text-secondary)]">{workflow.description || "未填写说明"}</p>
          </div>
          <span className={`sys-info-tag ${status.className}`}>{status.label}</span>
        </div>
        <div className="mt-4 grid grid-cols-3 gap-2">
          <CardMetric label="积木" value={String(workflow.nodeCount)} />
          <CardMetric label="暂停点" value={String(workflow.pausePointCount)} />
          <CardMetric label="负责人" value={workflow.ownerName || "-"} compact />
        </div>
        <p className="mt-4 text-xs text-[var(--color-text-tertiary)]">更新：{formatDateTime(workflow.updatedAt)}</p>
      </button>
      <div className="mt-4 flex flex-wrap gap-2 border-t border-[var(--color-border-light)] pt-3">
        <button type="button" onClick={onValidate} disabled={validating} className="sys-btn sys-btn--default sys-btn--sm">
          <ListChecks size={14} aria-hidden="true" />
          {validating ? "校验中" : "发布校验"}
        </button>
        <button type="button" onClick={onOpen} className="sys-btn sys-btn--primary sys-btn--sm">
          <PanelRightOpen size={14} aria-hidden="true" />
          配置积木
        </button>
      </div>
    </article>
  );
}

function WorkflowValidationCard({
  workflow,
  validating,
  onValidate,
  onOpen,
}: {
  workflow: WorkflowDraft;
  validating: boolean;
  onValidate: () => void;
  onOpen: () => void;
}) {
  const status = statusMeta[workflow.status];

  return (
    <article className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold text-[var(--color-text-primary)]">{workflow.name}</h3>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{workflow.nodeCount} 个积木 · {workflow.pausePointCount} 个暂停点</p>
        </div>
        <span className={`sys-info-tag ${status.className}`}>{status.label}</span>
      </div>
      <div className="mt-4 rounded-[var(--radius-md)] bg-[var(--color-bg-card)] p-3 text-sm leading-6 text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
        发布前需要确认输入参数、智能体能力引用、集群拼接规则和交付配置均已完成。
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        <button type="button" disabled={validating} onClick={onValidate} className="sys-btn sys-btn--primary sys-btn--sm">
          <ListChecks size={14} aria-hidden="true" />
          {validating ? "校验中" : "执行校验"}
        </button>
        <button type="button" onClick={onOpen} className="sys-btn sys-btn--default sys-btn--sm">
          <PanelRightOpen size={14} aria-hidden="true" />
          打开配置
        </button>
      </div>
    </article>
  );
}

function CardMetric({ label, value, compact = false }: { label: string; value: string; compact?: boolean }) {
  return (
    <div className="rounded-[var(--radius-md)] bg-[var(--color-bg-card)] px-2 py-2 ring-1 ring-[var(--color-border-light)]">
      <p className="text-[11px] text-[var(--color-text-tertiary)]">{label}</p>
      <p className={`mt-1 truncate font-semibold text-[var(--color-text-primary)] ${compact ? "text-xs" : "text-base"}`}>{value}</p>
    </div>
  );
}

function OverviewStat({ icon: Icon, label, value, tone }: { icon: typeof GitBranch; label: string; value: string; tone: "primary" | "success" | "info" | "cap" }) {
  return (
    <div className="sys-overview-stat">
      <div className={`sys-overview-stat-icon sys-overview-stat-icon--${tone}`}><Icon size={20} aria-hidden="true" /></div>
      <div>
        <div className="sys-overview-stat-value">{value}</div>
        <div className="sys-overview-stat-label">{label}</div>
      </div>
    </div>
  );
}


function formatDateTime(value: string) {
  if (!value) {
    return "-";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function getWorkflowErrorContext(error: unknown, tenantId?: string, extra?: Record<string, unknown>) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId, tenantId, ...extra };
  }

  return { message: error instanceof Error ? error.message : "unknown", tenantId, ...extra };
}
