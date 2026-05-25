import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  ArrowRight,
  CheckCircle2,
  ChevronDown,
  ClipboardCheck,
  Clock3,
  FilePlus2,
  GitBranch,
  ListChecks,
  PanelRightOpen,
  Share2,
  Search,
  UserRound,
  UsersRound,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Drawer, Pagination, Segmented, Select } from "antd";
import { AgentumApiError, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type {
  WorkflowDraftDetail,
  WorkflowDraftRow,
  WorkflowNodeDraft,
  WorkflowPublishValidationResult,
  WorkflowStatus,
  WorkflowVariableDraft,
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

type WorkflowDesignerTab = "overview" | "all" | "mine";
type WorkflowListScope = "all" | "mine" | "shared";
type WorkflowStatusFilter = WorkflowStatus | "all";

const workflowStatusOptions: Array<{ value: WorkflowStatusFilter; label: string }> = [
  { value: "all", label: "全部状态" },
  { value: "draft", label: "草稿" },
  { value: "published", label: "已发布" },
  { value: "review", label: "待校验" },
];

const workflowSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const workflowSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

function formatPaginationTotal(count: number, range: [number, number], pageSize: number): string {
  return count <= pageSize ? `共 ${count} 条` : `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`;
}

export function WorkflowDraftsPage() {
  // 草稿列表已接入工作流草稿 API；编辑态改为阶段积木编排，运行实例会在后续独立建模。
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const themeMode = useAuthStore((s) => s.themeMode);
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
  const [detailWorkflow, setDetailWorkflow] = useState<WorkflowDraft | null>(null);
  const [drawerDetail, setDrawerDetail] = useState<WorkflowDraftDetail | null>(null);
  const [drawerDetailLoading, setDrawerDetailLoading] = useState(false);
  const [drawerDetailError, setDrawerDetailError] = useState("");
  const [workflowStatusFilter, setWorkflowStatusFilter] = useState<WorkflowStatusFilter>("all");

  const currentUserId = user?.id ?? "";
  const currentScope: WorkflowListScope = activeTab === "mine" ? "mine" : activeTab === "all" ? "shared" : "all";
  const drawerRootClassName = themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer";

  const loadDrafts = useCallback(async (nextPage = 1, keyword = searchValue, nextPageSize = pageSize, scope: WorkflowListScope = currentScope, status: WorkflowStatusFilter = workflowStatusFilter) => {
    if (!token || !user?.tenantId) {
      setLoadError("当前账号缺少租户上下文，无法加载工作流草稿");
      setWorkflows([]);
      return;
    }

    setLoading(true);
    setLoadError("");

    try {
      const result = await workflowApi.listDrafts(user.tenantId, token, nextPage, nextPageSize, keyword, scope, status);
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
  }, [currentScope, pageSize, searchValue, token, user?.tenantId, workflowStatusFilter]);

  useEffect(() => {
    void loadDrafts(1, searchValue, pageSize, currentScope);
  }, [loadDrafts]);

  useEffect(() => {
    if (!detailWorkflow) {
      setDrawerDetail(null);
      setDrawerDetailError("");
      setDrawerDetailLoading(false);
      return;
    }

    if (!token || !user?.tenantId) {
      setDrawerDetailError("当前账号缺少租户上下文，无法加载流程内容");
      setDrawerDetail(null);
      return;
    }

    const tenantId = user.tenantId;
    let cancelled = false;
    setDrawerDetailLoading(true);
    setDrawerDetailError("");

    // 抽屉用于进入设计前快速看完整流程内容，只读取草稿详情，不触发保存或发布等写动作。
    void workflowApi.getDraft(tenantId, detailWorkflow.id, token)
      .then((detail) => {
        if (!cancelled) {
          setDrawerDetail(detail);
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        console.warn("[workflow] 工作流详情抽屉加载失败", getWorkflowErrorContext(error, tenantId, { workflowId: detailWorkflow.id }));
        setDrawerDetailError(error instanceof AgentumApiError ? error.message : "无法加载流程内容");
        setDrawerDetail(null);
      })
      .finally(() => {
        if (!cancelled) {
          setDrawerDetailLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [detailWorkflow, token, user?.tenantId]);

  const filteredWorkflows = useMemo(() => {
    if (activeTab === "all") {
      return workflows.filter((workflow) => !isWorkflowOwnedByCurrentUser(workflow, currentUserId));
    }
    if (activeTab === "mine") {
      return workflows.filter((workflow) => isWorkflowOwnedByCurrentUser(workflow, currentUserId));
    }
    return workflows;
  }, [activeTab, currentUserId, workflows]);
  const sharedWorkflows = useMemo(() => workflows.filter((workflow) => !isWorkflowOwnedByCurrentUser(workflow, currentUserId)), [currentUserId, workflows]);
  const myOwnedWorkflows = useMemo(() => workflows.filter((workflow) => isWorkflowOwnedByCurrentUser(workflow, currentUserId)), [currentUserId, workflows]);

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
      value: "all",
      label: (
        <span className="login-portal-option">
          <UsersRound className="login-portal-option-icon" aria-hidden="true" />
          <span>协作开放</span>
        </span>
      ),
    },
    {
      value: "mine",
      label: (
        <span className="login-portal-option">
          <UserRound className="login-portal-option-icon" aria-hidden="true" />
          <span>我的流程</span>
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
      setDetailWorkflow((current) => current?.id === workflow.id ? result.draft : current);
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
                面向租户内协作的流程能力库：可参与共享流程设计，也可以维护自己的流程草稿、校验和发布版本。
              </p>
            </div>
          </div>
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
            {activeTab === "overview" ? "功能入口与近期协作流程" : activeTab === "all" ? "他人开放给我的流程，可参与共享设计" : "我创建或维护的流程草稿与发布治理"}
          </div>
        </div>

        {activeTab === "overview" ? (
          <section className="sys-overview-stats mb-5" aria-label="工作流概览">
            <OverviewStat icon={GitBranch} label="可见工作流" value={String(total)} tone="primary" />
            <OverviewStat icon={Clock3} label="当前页草稿" value={String(draftCount)} tone="info" />
            <OverviewStat icon={CheckCircle2} label="当前页已发布" value={String(publishedCount)} tone="success" />
            <OverviewStat icon={ListChecks} label="当前页待校验" value={String(reviewCount)} tone="cap" />
          </section>
        ) : null}

        {activeTab === "overview" ? (
          <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]" aria-label="流程设计总览">
            <section className="sys-preview-card">
              <div className="sys-preview-card-title"><GitBranch size={16} /> 流程功能入口</div>
              <div className="grid gap-3 md:grid-cols-2">
                <WorkflowFeatureCard
                  icon={UsersRound}
                  title="协作开放"
                  description="查看他人开放给我的流程，先看详情抽屉，再进入具体积木设计。"
                  meta={`${sharedWorkflows.length} 个当前页协作流程`}
                  onClick={() => setActiveTab("all")}
                />
                <WorkflowFeatureCard
                  icon={UserRound}
                  title="我的流程"
                  description="处理自己创建或负责维护的流程草稿、发布校验和版本演进。"
                  meta={`${myOwnedWorkflows.length} 个当前页我的流程`}
                  onClick={() => setActiveTab("mine")}
                />
                <WorkflowFeatureCard
                  icon={ClipboardCheck}
                  title="发布治理"
                  description="进入流程抽屉后执行校验，查看阻塞项，通过后冻结正式版本。"
                  meta={`${reviewCount} 个当前页待校验`}
                  onClick={() => {
                    setWorkflowStatusFilter("review");
                    setActiveTab("mine");
                  }}
                />
                <WorkflowFeatureCard
                  icon={FilePlus2}
                  title="新建流程"
                  description="创建新的流程草稿，随后进入阶段积木设计并引用能力资产。"
                  meta="创建后归入我的流程"
                  onClick={() => setIsCreating(true)}
                />
              </div>
            </section>

            <aside className="sys-preview-card">
              <div className="sys-preview-card-title"><Share2 size={16} /> 近期协作流程</div>
              <div className="space-y-3">
                <WorkflowSideList
                  title="协作开放"
                  empty="暂无他人开放给我的流程"
                  workflows={sharedWorkflows.slice(0, 3)}
                  onOpen={(workflow) => setDetailWorkflow(workflow)}
                />
                <WorkflowSideList
                  title="我的流程"
                  empty="暂无最近维护的我的流程"
                  workflows={myOwnedWorkflows.slice(0, 3)}
                  onOpen={(workflow) => setDetailWorkflow(workflow)}
                />
              </div>
            </aside>
          </section>
        ) : null}

        {activeTab === "all" || activeTab === "mine" ? (
          <section className="sys-fade-in" aria-labelledby="workflow-list-title">
            <div className="workflow-library-toolbar">
              <div className="workflow-library-toolbar-actions">
                <label className="workflow-definition-search">
                  <Search className="h-[18px] w-[18px]" aria-hidden="true" />
                  <span className="sr-only">搜索工作流</span>
                  <input
                    value={searchValue}
                    onChange={(event) => {
                      setSearchValue(event.target.value);
                      setPage(1);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        void loadDrafts(1, searchValue, pageSize, currentScope, workflowStatusFilter);
                      }
                    }}
                    placeholder="搜索名称或说明"
                  />
                </label>
                <Select
                  className="agent-admin-select workflow-status-select"
                  classNames={workflowSelectClassNames}
                  suffixIcon={workflowSelectSuffixIcon}
                  value={workflowStatusFilter}
                  options={workflowStatusOptions}
                  onChange={(value) => setWorkflowStatusFilter(value as WorkflowStatusFilter)}
                />
                <button type="button" className="sys-btn sys-btn--default" onClick={() => void loadDrafts(1, searchValue, pageSize, currentScope, workflowStatusFilter)}>
                  <Search size={18} aria-hidden="true" />
                  查询
                </button>
              </div>
            </div>

            {loadError ? <div className="workflow-feedback workflow-feedback--danger">{loadError}</div> : null}
            {validationError ? <div className="workflow-feedback workflow-feedback--danger">{validationError}</div> : null}
            {publishSuccess ? <div className="workflow-feedback workflow-feedback--success">{publishSuccess}</div> : null}

            <div className="sys-card-grid">
              {filteredWorkflows.map((workflow) => (
                <WorkflowDesignCard
                  key={workflow.id}
                  workflow={workflow}
                  mine={isWorkflowOwnedByCurrentUser(workflow, currentUserId)}
                  validating={validatingWorkflowId === workflow.id}
                  onOpenDetail={() => setDetailWorkflow(workflow)}
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
                <p>{activeTab === "mine" ? "还没有我的流程" : "没有找到匹配的流程"}</p>
                <span>{activeTab === "mine" ? "可以新建流程草稿，或查看协作开放流程参与设计。" : "可以调整搜索词或筛选条件。"}</span>
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
                  onChange={(nextPage, nextPageSize) => void loadDrafts(nextPage, searchValue, nextPageSize, currentScope, workflowStatusFilter)}
                  onShowSizeChange={(nextPage, nextPageSize) => void loadDrafts(nextPage, searchValue, nextPageSize, currentScope, workflowStatusFilter)}
                />
              </div>
            ) : null}
          </section>
        ) : null}
      </div>

      <Drawer
        title={detailWorkflow ? "流程详情" : "流程详情"}
        placement="right"
        width={560}
        onClose={() => setDetailWorkflow(null)}
        open={Boolean(detailWorkflow)}
        rootClassName={drawerRootClassName}
      >
        {detailWorkflow ? (
          <>
            <div className="sys-drawer-section">
              <div className="workflow-detail-drawer-head">
                <span className="workflow-detail-drawer-icon">
                  <GitBranch size={22} aria-hidden="true" />
                </span>
                <div className="min-w-0">
                  <div className="sys-info-tags mb-2">
                    <span className={`sys-info-tag ${statusMeta[detailWorkflow.status].className}`}>{statusMeta[detailWorkflow.status].label}</span>
                    <span className="sys-info-tag">{isWorkflowOwnedByCurrentUser(detailWorkflow, currentUserId) ? "我的流程" : "协作开放"}</span>
                  </div>
                  <h2 className="workflow-detail-drawer-title">{detailWorkflow.name}</h2>
                  <p className="agent-muted mt-2 text-sm leading-6">{detailWorkflow.description || "暂无说明"}</p>
                </div>
              </div>

              <div className="sys-config-group">
                <div className="sys-form-row">
                  <span className="sys-form-label">负责人</span>
                  <span className="sys-form-value">{detailWorkflow.ownerName || "未知用户"}</span>
                </div>
                <div className="sys-form-row">
                  <span className="sys-form-label">积木数量</span>
                  <span className="sys-form-value">{detailWorkflow.nodeCount} 个</span>
                </div>
                <div className="sys-form-row">
                  <span className="sys-form-label">更新时间</span>
                  <span className="sys-form-value">{formatDateTime(detailWorkflow.updatedAt)}</span>
                </div>
              </div>

              <WorkflowDrawerContent
                detail={drawerDetail}
                loading={drawerDetailLoading}
                error={drawerDetailError}
              />
            </div>

            <div className="sys-drawer-footer">
              <div className="sys-drawer-footer-right">
                <button type="button" className="sys-btn sys-btn--default" onClick={() => setDetailWorkflow(null)}>
                  <X size={14} aria-hidden="true" />
                  关闭
                </button>
                <button type="button" className="sys-btn sys-btn--default" disabled={validatingWorkflowId === detailWorkflow.id} onClick={() => void handleValidateForPublish(detailWorkflow)}>
                  <ListChecks size={14} aria-hidden="true" />
                  {validatingWorkflowId === detailWorkflow.id ? "校验中" : "发布校验"}
                </button>
                <button type="button" className="sys-btn sys-btn--primary" onClick={() => { setEditingWorkflow(detailWorkflow); setDetailWorkflow(null); }}>
                  <PanelRightOpen size={14} aria-hidden="true" />
                  进入设计
                </button>
              </div>
            </div>
          </>
        ) : null}
      </Drawer>

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

function WorkflowFeatureCard({
  icon: Icon,
  title,
  description,
  meta,
  onClick,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  meta: string;
  onClick: () => void;
}) {
  return (
    <button type="button" onClick={onClick} className="workflow-feature-card">
      <span className="workflow-feature-card-head">
        <span className="workflow-feature-card-icon">
          <Icon size={16} aria-hidden="true" />
        </span>
        <span className="workflow-feature-card-title">{title}</span>
      </span>
      <span className="workflow-feature-card-description">{description}</span>
      <span className="workflow-feature-card-meta">
        {meta}
        <ArrowRight size={14} aria-hidden="true" />
      </span>
    </button>
  );
}

function WorkflowSideList({ title, empty, workflows, onOpen }: { title: string; empty: string; workflows: WorkflowDraft[]; onOpen: (workflow: WorkflowDraft) => void }) {
  return (
    <div className="workflow-side-list">
      <h3>{title}</h3>
      <div className="mt-3 space-y-2">
        {workflows.length === 0 ? (
          <p className="agent-muted text-sm">{empty}</p>
        ) : (
          workflows.map((workflow) => (
            <WorkflowPreviewItem key={workflow.id} workflow={workflow} onClick={() => onOpen(workflow)} />
          ))
        )}
      </div>
    </div>
  );
}

function WorkflowPreviewItem({ workflow, onClick }: { workflow: WorkflowDraft; onClick: () => void }) {
  const status = statusMeta[workflow.status];

  return (
    <button type="button" className="sys-preview-item workflow-preview-item" onClick={onClick}>
      <span className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <GitBranch size={16} aria-hidden="true" />
        </span>
        <span className="min-w-0">
          <span className="sys-preview-item-name">{workflow.name}</span>
          <span className="sys-preview-item-sub">{workflow.ownerName || "未知用户"} · {workflow.nodeCount} 个积木</span>
        </span>
      </span>
      <span className={`sys-info-tag ${status.className}`}>{status.label}</span>
    </button>
  );
}

function isWorkflowOwnedByCurrentUser(workflow: WorkflowDraft, currentUserId: string) {
  return Boolean(currentUserId) && workflow.ownerId === currentUserId;
}

function WorkflowDesignCard({
  workflow,
  mine,
  validating,
  onOpenDetail,
  onValidate,
}: {
  workflow: WorkflowDraft;
  mine: boolean;
  validating: boolean;
  onOpenDetail: () => void;
  onValidate: () => void;
}) {
  const status = statusMeta[workflow.status];

  return (
    <article className="sys-card workflow-design-card" onClick={onOpenDetail}>
      <div className="sys-card-header">
        <div className="sys-card-avatar sys-card-avatar--cap">
          <GitBranch size={22} aria-hidden="true" />
        </div>
        <div className="sys-card-info">
          <div className="sys-card-name">{workflow.name}</div>
          <div className="sys-card-code">{workflow.ownerName || "未知用户"} · {formatDateTime(workflow.updatedAt)}</div>
        </div>
        <span className={`sys-status ${workflow.status === "published" ? "sys-status--active" : "sys-status--inactive"}`}>
          <span className="sys-status-dot" />
          {status.label}
        </span>
      </div>
      <div className="sys-info-tags">
        <span className="sys-info-tag sys-info-tag--primary">{mine ? "我的流程" : "协作开放"}</span>
        <span className="sys-info-tag">{workflow.nodeCount} 个积木</span>
      </div>
      <p className="agent-muted workflow-design-card-desc">{workflow.description || "暂无说明"}</p>
      <div className="sys-card-meta">
        <div className="sys-meta-item">
          <span className="sys-meta-label">协作方式</span>
          <span className="sys-meta-value">{mine ? "本人维护" : "可参与设计"}</span>
        </div>
        <div className="sys-meta-item">
          <span className="sys-meta-label">发布动作</span>
          <span className="sys-meta-value">{workflow.status === "published" ? "可继续演进草稿" : "需校验后发布"}</span>
        </div>
      </div>
      <div className="sys-card-footer">
        <span className="sys-card-footer-time"><Clock3 size={12} /> 点击查看详情</span>
        <div className="sys-card-footer-actions" onClick={(event) => event.stopPropagation()}>
          <button type="button" disabled={validating} onClick={onValidate} className="sys-btn sys-btn--text sys-btn--sm">
            <ListChecks size={14} aria-hidden="true" />
            {validating ? "校验中" : "校验"}
          </button>
          <button type="button" onClick={onOpenDetail} className="sys-btn sys-btn--text sys-btn--sm">
            <PanelRightOpen size={14} aria-hidden="true" />
            详情
          </button>
        </div>
      </div>
    </article>
  );
}

function WorkflowDrawerContent({
  detail,
  loading,
  error,
}: {
  detail: WorkflowDraftDetail | null;
  loading: boolean;
  error: string;
}) {
  if (loading) {
    return (
      <div className="workflow-drawer-loading">
        <Clock3 size={16} aria-hidden="true" />
        正在读取流程内容
      </div>
    );
  }

  if (error) {
    return <div className="workflow-feedback workflow-feedback--danger">{error}</div>;
  }

  const nodes = sortDrawerNodes(detail?.nodes ?? []);
  const variables = detail?.variables ?? [];

  return (
    <div className="workflow-drawer-overview">
      <section className="workflow-drawer-block">
        <h3>流程内容</h3>
        {nodes.length === 0 ? (
          <p className="agent-muted text-sm leading-6">当前还没有配置积木，进入设计后可以从输入节点、智能体节点、智能体集群和交付节点开始搭建。</p>
        ) : (
          <div className="workflow-drawer-step-list">
            {nodes.map((node, index) => (
              <WorkflowDrawerStep key={node.nodeId} node={node} index={index} />
            ))}
          </div>
        )}
      </section>

      <section className="workflow-drawer-block">
        <h3>输出变量</h3>
        {variables.length === 0 ? (
          <p className="agent-muted text-sm">暂无变量声明</p>
        ) : (
          <div className="workflow-drawer-variable-list">
            {variables.slice(0, 12).map((variable) => (
              <WorkflowDrawerVariable key={`${variable.sourceNode}-${variable.name}`} variable={variable} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function WorkflowDrawerStep({ node, index }: { node: WorkflowNodeDraft; index: number }) {
  const nodeType = formatWorkflowNodeType(node.nodeType);
  const summary = readWorkflowConfigString(node.config.summary, node.outputVariables.length > 0 ? `输出 ${node.outputVariables.join("、")}` : "尚未配置输出参数");

  return (
    <article className="workflow-drawer-step">
      <span className="workflow-drawer-step-index">{index + 1}</span>
      <span className="min-w-0">
        <strong>{node.name}</strong>
        <small>{nodeType} · {summary}</small>
      </span>
    </article>
  );
}

function WorkflowDrawerVariable({ variable }: { variable: WorkflowVariableDraft }) {
  return (
    <span className="workflow-drawer-variable">
      <strong>{variable.name}</strong>
      <small>{formatVariableType(variable.type)}</small>
    </span>
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

function sortDrawerNodes(nodes: WorkflowNodeDraft[]) {
  return [...nodes]
    .filter((node) => node.nodeType !== "trigger")
    .sort((left, right) => left.positionX - right.positionX || left.positionY - right.positionY);
}

function readWorkflowConfigString(value: unknown, fallback: string) {
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function formatWorkflowNodeType(nodeType: WorkflowNodeDraft["nodeType"]) {
  const labels: Record<WorkflowNodeDraft["nodeType"], string> = {
    trigger: "系统触发",
    user_input: "输入节点",
    agent: "单智能体节点",
    parallel_group: "智能体集群节点",
    merge: "组装节点",
    condition: "条件分支",
    human_review: "人工审核",
    delivery: "交付节点",
  };

  return labels[nodeType];
}

function formatVariableType(type: WorkflowVariableDraft["type"]) {
  const labels: Record<WorkflowVariableDraft["type"], string> = {
    string: "文本",
    number: "数字",
    object: "对象",
    array: "数组",
    boolean: "布尔",
    decision: "决策",
    file: "文件",
  };

  return labels[type];
}

function getWorkflowErrorContext(error: unknown, tenantId?: string, extra?: Record<string, unknown>) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId, tenantId, ...extra };
  }

  return { message: error instanceof Error ? error.message : "unknown", tenantId, ...extra };
}
