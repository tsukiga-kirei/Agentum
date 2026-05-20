import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  FilePlus2,
  GitBranch,
  ListChecks,
  X,
  PanelRightOpen,
  Search,
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
  const moduleOptions = [{
    value: "definitions",
    label: (
      <span className="login-portal-option">
        <GitBranch className="login-portal-option-icon" aria-hidden="true" />
        <span>工作流定义</span>
      </span>
    ),
  }];

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
              value="definitions"
              options={moduleOptions}
              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
            />
          </div>
          <div className="login-portal-description login-portal-description--business">
            <span className="login-portal-description-dot" />
            草稿、发布状态与积木编排入口
          </div>
        </div>

        <section className="sys-overview-stats mb-5" aria-label="工作流概览">
          <OverviewStat icon={GitBranch} label="全部工作流" value={String(total)} tone="primary" />
          <OverviewStat icon={Clock3} label="当前页草稿" value={String(draftCount)} tone="info" />
          <OverviewStat icon={CheckCircle2} label="当前页已发布" value={String(publishedCount)} tone="success" />
          <OverviewStat icon={ListChecks} label="当前页待校验" value={String(reviewCount)} tone="cap" />
        </section>

        <section className="overflow-hidden rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]" aria-labelledby="workflow-list-title">
          <div className="p-5">
            <div className="sys-hint mb-4">
              <ListChecks size={14} />
              发布前会重新校验节点、连线和变量声明；正式发布后，当前版本只读保留，避免运行中的流程被草稿改动影响。
            </div>

            <div className="workflow-definition-table-card">
              <div className="workflow-definition-toolbar">
                <div>
                  <h2 id="workflow-list-title">工作流列表</h2>
                  <p>查看定义状态、继续编辑阶段积木或执行发布校验</p>
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

              <div className="workflow-definition-table-wrap">
                <table className="workflow-definition-table">
                  <thead>
                    <tr>
                      <th>工作流</th>
                      <th>状态</th>
                      <th>负责人</th>
                      <th>结构</th>
                      <th>更新时间</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredWorkflows.map((workflow) => {
                      const status = statusMeta[workflow.status];
                      return (
                        <tr key={workflow.id}>
                          <td>
                            <div className="workflow-definition-name">{workflow.name}</div>
                            <div className="workflow-definition-description">{workflow.description || "未填写说明"}</div>
                          </td>
                          <td><span className={`sys-info-tag ${status.className}`}>{status.label}</span></td>
                          <td>{workflow.ownerName}</td>
                          <td>
                            <div className="workflow-definition-metrics">
                              <span>{workflow.nodeCount} 个积木</span>
                              <span>{workflow.pausePointCount} 个暂停点</span>
                            </div>
                          </td>
                          <td>{formatDateTime(workflow.updatedAt)}</td>
                          <td>
                            <div className="workflow-definition-actions">
                              <button type="button" disabled={validatingWorkflowId === workflow.id} onClick={() => void handleValidateForPublish(workflow)} className="sys-btn sys-btn--default sys-btn--sm">
                                <ListChecks size={14} aria-hidden="true" />
                                {validatingWorkflowId === workflow.id ? "校验中" : "发布校验"}
                              </button>
                              <button type="button" onClick={() => setEditingWorkflow(workflow)} className="sys-btn sys-btn--primary sys-btn--sm">
                                <PanelRightOpen size={14} aria-hidden="true" />
                                打开设计
                              </button>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
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
          </div>
        </section>
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
