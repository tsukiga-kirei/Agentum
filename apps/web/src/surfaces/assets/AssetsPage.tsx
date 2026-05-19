import { useCallback, useEffect, useMemo, useState } from "react";
import { Bot, Boxes, BrainCircuit, CheckCircle2, ChevronDown, Clock, Eye, FileText, Hash, Library, PlusCircle, Search, ShieldCheck, Tag, UserRoundCog, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Empty, Modal, Pagination, Segmented, Select, Spin, message } from "antd";
import { AgentumApiError, assetApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AssetSummary, AssetType, CreateMyAssetRequest, MyAssetRow, SystemCapabilityAssetRow } from "../../types/asset";

type AssetTab = "overview" | "system" | "mine";

type PageState = {
  page: number;
  size: number;
  total: number;
};

const defaultPageState: PageState = {
  page: 1,
  size: 10,
  total: 0,
};

const assetTypeOptions: Array<{ value: AssetType; label: string }> = [
  { value: "agent_template", label: "智能体模板" },
  { value: "skill", label: "Skill" },
  { value: "mcp", label: "MCP" },
  { value: "prompt_template", label: "提示词模板" },
  { value: "delivery", label: "交付能力" },
];

const riskOptions = [
  { value: "low", label: "低风险" },
  { value: "medium", label: "中风险" },
  { value: "high", label: "高风险" },
];

const visibilityOptions = [
  { value: "private", label: "仅自己维护" },
  { value: "tenant", label: "租户内复用" },
];

const paginationLocale = {
  items_per_page: "条/页",
  jump_to: "跳至",
  jump_to_confirm: "确定",
  page: "页",
  prev_page: "上一页",
  next_page: "下一页",
};

const assetTabs: Array<{ key: AssetTab; label: string; icon: LucideIcon; description: string }> = [
  { key: "overview", label: "总览", icon: Library, description: "查看系统能力、租户分配和自建资产的整体勾稽" },
  { key: "system", label: "对我开放", icon: Boxes, description: "租户管理已分配给当前用户、部门或角色的能力，可直接进入资产创建和流程设计引用" },
  { key: "mine", label: "我的能力", icon: Bot, description: "沉淀我在当前租户内创建的业务能力草稿和后续发布资产" },
];

const adminSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const adminSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

export function AssetsPage() {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const themeMode = useAuthStore((s) => s.themeMode);
  const [messageApi, messageContextHolder] = message.useMessage();

  const [activeTab, setActiveTab] = useState<AssetTab>("overview");
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<AssetSummary | null>(null);
  const [systemAssets, setSystemAssets] = useState<SystemCapabilityAssetRow[]>([]);
  const [myAssets, setMyAssets] = useState<MyAssetRow[]>([]);
  const [systemPage, setSystemPage] = useState<PageState>(defaultPageState);
  const [minePage, setMinePage] = useState<PageState>(defaultPageState);
  const [keyword, setKeyword] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [draft, setDraft] = useState<CreateMyAssetRequest>({
    assetType: "prompt_template",
    name: "",
    code: "",
    version: "v1",
    description: "",
    riskLevel: "low",
    visibility: "private",
  });

  const tenantId = user?.tenantId ?? "";
  const modalRootClassName = themeMode === "dark" ? "agent-dark-modal" : undefined;
  const activeTabMeta = assetTabs.find((tab) => tab.key === activeTab) ?? assetTabs[0];
  const tabSegmentedOptions = assetTabs.map((tab) => {
    const Icon = tab.icon;
    return {
      value: tab.key,
      label: (
        <span className="login-portal-option">
          <Icon className="login-portal-option-icon" aria-hidden="true" />
          <span>{tab.label}</span>
        </span>
      ),
    };
  });

  const handleApiError = useCallback(
    (error: unknown, fallback: string) => {
      if (error instanceof AgentumApiError) {
        messageApi.error(error.message);
        console.warn("[assets] 能力资产请求失败", { code: error.code, requestId: error.requestId, tenantId });
        return;
      }
      messageApi.error(fallback);
      console.error("[assets] 能力资产请求异常", { message: error instanceof Error ? error.message : "unknown", tenantId });
    },
    [messageApi, tenantId],
  );

  const loadSummary = useCallback(async () => {
    if (!token || !tenantId) return;
    try {
      setSummary(await assetApi.summary(tenantId, token));
    } catch (error) {
      handleApiError(error, "加载能力资产总览失败");
    }
  }, [handleApiError, tenantId, token]);

  const loadSystemAssets = useCallback(
    async (page = systemPage.page, size = systemPage.size) => {
      if (!token || !tenantId) return;
      setLoading(true);
      try {
        const response = await assetApi.listSystemCapabilities(tenantId, token, page, size);
        setSystemAssets(response.items);
        setSystemPage({ page: response.page, size: response.size, total: response.total });
      } catch (error) {
        handleApiError(error, "加载对我开放能力失败");
      } finally {
        setLoading(false);
      }
    },
    [handleApiError, systemPage.page, systemPage.size, tenantId, token],
  );

  const loadMyAssets = useCallback(
    async (page = minePage.page, size = minePage.size, search = keyword) => {
      if (!token || !tenantId) return;
      setLoading(true);
      try {
        const response = await assetApi.listMine(tenantId, token, search, page, size);
        setMyAssets(response.items);
        setMinePage({ page: response.page, size: response.size, total: response.total });
      } catch (error) {
        handleApiError(error, "加载我的能力失败");
      } finally {
        setLoading(false);
      }
    },
    [handleApiError, keyword, minePage.page, minePage.size, tenantId, token],
  );

  useEffect(() => {
    void loadSummary();
    void loadSystemAssets(1, systemPage.size);
    void loadMyAssets(1, minePage.size, "");
    // 首次进入页面要同时拿到三个页签的摘要，方便总览卡片表达链路状态。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, tenantId]);

  useEffect(() => {
    if (activeTab === "system") {
      void loadSystemAssets();
    }
    if (activeTab === "mine") {
      void loadMyAssets();
    }
  }, [activeTab, loadMyAssets, loadSystemAssets]);

  const assignedSystemAssets = useMemo(() => systemAssets.filter((asset) => asset.assignedToMe), [systemAssets]);
  const draftAssets = useMemo(() => myAssets.filter((asset) => asset.status === "draft"), [myAssets]);

  const handleCreate = async () => {
    if (!token || !tenantId) {
      messageApi.warning("请先进入有效租户后再创建能力");
      return;
    }
    if (!draft.name.trim() || !draft.code.trim()) {
      messageApi.warning("请输入能力名称和编码");
      return;
    }

    setSubmitting(true);
    try {
      await assetApi.createMine(tenantId, token, {
        ...draft,
        name: draft.name.trim(),
        code: draft.code.trim(),
        version: draft.version?.trim() || "v1",
        description: draft.description?.trim(),
      });
      messageApi.success("能力资产已创建");
      setCreateOpen(false);
      setDraft({ assetType: "prompt_template", name: "", code: "", version: "v1", description: "", riskLevel: "low", visibility: "private" });
      await Promise.all([loadSummary(), loadMyAssets(1, minePage.size, keyword)]);
      setActiveTab("mine");
    } catch (error) {
      handleApiError(error, "创建能力资产失败");
    } finally {
      setSubmitting(false);
    }
  };

  if (!tenantId) {
    return (
      <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
        <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
          <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
            <div className="flex min-w-0 gap-4">
              <div className="asset-mgmt-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
                <Library className="h-6 w-6" aria-hidden="true" />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">能力资产</h1>
                  <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                    业务能力治理
                  </span>
                </div>
                <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                  能力资产需要明确租户上下文，用于连接系统能力池、租户分配和流程节点引用。
                </p>
              </div>
            </div>
          </header>
          <Empty description="系统管理入口不绑定租户，请切换到业务用户或租户管理角色后查看能力资产" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
      {messageContextHolder}
      <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
        <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex min-w-0 gap-4">
            <div className="asset-mgmt-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
              <Library className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">能力资产</h1>
                <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                  业务能力治理
                </span>
              </div>
              <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                连接系统管理开放能力、租户管理分配能力和流程设计节点引用，让能力从平台底座一路进入业务编排。
              </p>
            </div>
          </div>
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => setCreateOpen(true)}>
            <PlusCircle size={15} />
            新建能力
          </button>
        </header>

        <div className="system-mgmt-module-switch mb-5">
          <div className="system-mgmt-segmented-scroll">
            <Segmented
              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
              value={activeTab}
              onChange={(value) => setActiveTab(value as AssetTab)}
              options={tabSegmentedOptions}
            />
          </div>
          <div className="login-portal-description login-portal-description--business">
            <span className="login-portal-description-dot" />
            {activeTabMeta.description}
          </div>
        </div>

        <Spin spinning={loading}>
          {activeTab === "overview" ? (
            <OverviewPanel summary={summary} systemAssets={assignedSystemAssets} myAssets={myAssets} draftAssets={draftAssets} />
          ) : null}

          {activeTab === "system" ? (
            <section className="sys-fade-in">
              <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
                <p className="m-0 text-sm text-[var(--color-text-tertiary)]">这里仅展示租户管理已经开放给当前用户、部门或角色的能力；只进入租户池但未分配给当前主体的能力不会出现在业务视图中。</p>
              </div>
              {systemAssets.length === 0 ? (
                <div className="sys-preview-card">
                  <Empty description="当前暂无对我开放的能力" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                </div>
              ) : (
                <div className="sys-card-grid">
                  {systemAssets.map((asset) => <SystemAssetCard key={asset.id} asset={asset} />)}
                </div>
              )}
              <AssetPagination pageState={systemPage} onChange={(page, size) => void loadSystemAssets(page, size)} />
            </section>
          ) : null}

          {activeTab === "mine" ? (
            <section className="sys-fade-in">
              <div className="mb-5 flex flex-col gap-3 rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
                <div>
                  <h3 className="text-sm font-semibold">我的能力资产</h3>
                  <p className="agent-muted mt-1 text-xs">先以草稿沉淀，后续发布和分配后进入流程节点引用链路。</p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <div className="sys-field-input-wrap min-w-[260px]">
                    <Search size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input
                      className="sys-field-input"
                      value={keyword}
                      placeholder="搜索名称、编码或说明"
                      onChange={(event) => setKeyword(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") void loadMyAssets(1, minePage.size, keyword);
                      }}
                    />
                  </div>
                  <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => void loadMyAssets(1, minePage.size, keyword)}>
                    <Search size={14} />
                    查询
                  </button>
                </div>
              </div>
              {myAssets.length === 0 ? (
                <div className="sys-preview-card">
                  <Empty description="还没有自建能力" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                </div>
              ) : (
                <div className="sys-card-grid">
                  {myAssets.map((asset) => <MyAssetCard key={asset.id} asset={asset} />)}
                </div>
              )}
              <AssetPagination pageState={minePage} onChange={(page, size) => void loadMyAssets(page, size, keyword)} />
            </section>
          ) : null}
        </Spin>
      </div>

      <Modal
        open={createOpen}
        title={<span className="sys-modal-title">新建我的能力</span>}
        footer={null}
        rootClassName={modalRootClassName}
        onCancel={() => setCreateOpen(false)}
      >
        <div className="sys-field">
          <label className="sys-field-label sys-field-label--required">能力类型</label>
          <Select
            className="agent-admin-select w-full"
            classNames={adminSelectClassNames}
            prefix={<Boxes className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
            suffixIcon={adminSelectSuffixIcon}
            value={draft.assetType}
            options={assetTypeOptions}
            onChange={(assetType) => setDraft((current) => ({ ...current, assetType }))}
          />
        </div>
        <div className="sys-field-row">
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">能力名称</label>
            <div className="sys-field-input-wrap">
              <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
              <input className="sys-field-input" value={draft.name} placeholder="例如：续约风险追问模板" onChange={(event) => setDraft((current) => ({ ...current, name: event.target.value }))} />
            </div>
          </div>
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">能力编码</label>
            <div className="sys-field-input-wrap">
              <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
              <input className="sys-field-input" value={draft.code} placeholder="renewal_risk_prompt" onChange={(event) => setDraft((current) => ({ ...current, code: event.target.value }))} />
            </div>
          </div>
        </div>
        <div className="sys-field-row">
          <div className="sys-field">
            <label className="sys-field-label">版本</label>
            <div className="sys-field-input-wrap">
              <FileText size={16} className="sys-field-prefix" aria-hidden="true" />
              <input className="sys-field-input" value={draft.version ?? "v1"} onChange={(event) => setDraft((current) => ({ ...current, version: event.target.value }))} />
            </div>
          </div>
          <div className="sys-field">
            <label className="sys-field-label">风险等级</label>
            <Select
              className="agent-admin-select w-full"
              classNames={adminSelectClassNames}
              prefix={<ShieldCheck className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
              suffixIcon={adminSelectSuffixIcon}
              value={draft.riskLevel}
              options={riskOptions}
              onChange={(riskLevel) => setDraft((current) => ({ ...current, riskLevel }))}
            />
          </div>
        </div>
        <div className="sys-field">
          <label className="sys-field-label">可见范围</label>
          <Select
            className="agent-admin-select w-full"
            classNames={adminSelectClassNames}
            prefix={<Eye className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
            suffixIcon={adminSelectSuffixIcon}
            value={draft.visibility}
            options={visibilityOptions}
            onChange={(visibility) => setDraft((current) => ({ ...current, visibility }))}
          />
        </div>
        <div className="sys-field">
          <label className="sys-field-label">说明</label>
          <textarea className="sys-field-textarea" value={draft.description ?? ""} placeholder="说明这项能力的业务用途、输入约束和后续发布方向" onChange={(event) => setDraft((current) => ({ ...current, description: event.target.value }))} />
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" className="sys-btn sys-btn--default" onClick={() => setCreateOpen(false)}>
            <X size={14} />
            取消
          </button>
          <button type="button" className="sys-btn sys-btn--primary" disabled={submitting} onClick={() => void handleCreate()}>
            <PlusCircle size={14} />
            创建能力
          </button>
        </div>
      </Modal>
    </div>
  );
}

function OverviewPanel({
  summary,
  systemAssets,
  myAssets,
  draftAssets,
}: {
  summary: AssetSummary | null;
  systemAssets: SystemCapabilityAssetRow[];
  myAssets: MyAssetRow[];
  draftAssets: MyAssetRow[];
}) {
  return (
    <div className="sys-fade-in">
      <div className="sys-overview-stats">
        <OverviewStat icon={CheckCircle2} value={summary?.openedToMeSystemTotal ?? 0} label="对我开放能力" tone="success" />
        <OverviewStat icon={Boxes} value={summary?.tenantSystemPoolTotal ?? 0} label="租户能力池" tone="primary" />
        <OverviewStat icon={Library} value={summary?.myAssetTotal ?? 0} label="我的自建能力" tone="cap" />
        <OverviewStat icon={BrainCircuit} value={draftAssets.length} label="待完善草稿" tone="info" />
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <section className="sys-preview-card">
          <div className="sys-preview-card-title"><ShieldCheck size={16} /> 能力进入业务的路径</div>
          <div className="grid gap-3 lg:grid-cols-2">
            <PathStep icon={Boxes} title="系统管理开放" detail="登记全局 MCP、Skill、提示词模板和交付能力，并放入租户可用能力池。" />
            <PathStep icon={UserRoundCog} title="租户管理分配" detail="按角色、部门或人员分配能力池，形成业务侧可见和可引用范围。" />
            <PathStep icon={Library} title="能力资产沉淀" detail="业务成员创建智能体模板和业务能力，先以草稿进入当前租户资产台账。" />
            <PathStep icon={Bot} title="流程节点引用" detail="流程设计只引用能力版本，运行审计可追溯当时使用的资产与能力来源。" />
          </div>
        </section>

        <aside className="sys-preview-card">
          <div className="sys-preview-card-title"><BrainCircuit size={16} /> 近期治理</div>
          <div className="space-y-3">
            <SideList title="对我开放的系统能力" empty="暂无开放给我的能力" items={systemAssets.slice(0, 4).map((asset) => `${formatAssetType(asset.assetType)} · ${asset.name}`)} />
            <SideList title="待完善能力草稿" empty="暂无待完善的能力草稿" items={draftAssets.slice(0, 4).map((asset) => `${formatAssetType(asset.assetType)} · ${asset.name}`)} />
            <SideList title="最近创建的能力" empty="暂无最近创建的能力" items={myAssets.slice(0, 4).map((asset) => `${formatAssetType(asset.assetType)} · ${asset.name}`)} />
          </div>
        </aside>
      </div>
    </div>
  );
}

function OverviewStat({ icon: Icon, value, label, tone }: { icon: LucideIcon; value: number; label: string; tone: "primary" | "success" | "info" | "cap" }) {
  return (
    <div className="sys-overview-stat">
      <div className={`sys-overview-stat-icon sys-overview-stat-icon--${tone}`}><Icon size={20} /></div>
      <div>
        <div className="sys-overview-stat-value">{value}</div>
        <div className="sys-overview-stat-label">{label}</div>
      </div>
    </div>
  );
}

function PathStep({ icon: Icon, title, detail }: { icon: LucideIcon; title: string; detail: string }) {
  return (
    <article className="asset-path-step rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
      <div className="flex items-center gap-2">
        <span className="asset-path-step-icon sys-preview-item-icon sys-card-avatar--cap"><Icon size={16} /></span>
        <h3 className="asset-path-step-title text-sm font-semibold">{title}</h3>
      </div>
      <p className="asset-path-step-detail agent-muted mt-3 text-sm leading-6">{detail}</p>
    </article>
  );
}

function SystemAssetCard({ asset }: { asset: SystemCapabilityAssetRow }) {
  return (
    <article className="sys-card sys-card--static">
      <div className="sys-card-header">
        <div className="sys-card-avatar sys-card-avatar--cap"><AssetTypeIcon type={asset.assetType} /></div>
        <div className="sys-card-info">
          <div className="sys-card-name">{asset.name}</div>
          <div className="sys-card-code">{asset.code} · {asset.version}</div>
        </div>
        <span className={`sys-status ${asset.assignedToMe ? "sys-status--active" : "sys-status--inactive"}`}>
          <span className="sys-status-dot" />
          {asset.assignedToMe ? "可引用" : "未分配"}
        </span>
      </div>
      <div className="sys-info-tags">
        <span className="sys-info-tag sys-info-tag--primary">{formatAssetType(asset.assetType)}</span>
        <RiskTag level={asset.riskLevel} />
      </div>
      <div className="sys-card-meta">
        <div className="sys-meta-item">
          <span className="sys-meta-label">分配状态</span>
          <span className="sys-meta-value">{asset.assignmentScope}</span>
        </div>
        <div className="sys-meta-item">
          <span className="sys-meta-label">当前状态</span>
          <span className="sys-meta-value">{formatStatus(asset.status)}</span>
        </div>
      </div>
      <div className="sys-card-footer">
        <span className="sys-card-footer-time"><Clock size={12} /> 系统开放能力</span>
        <div className="sys-card-footer-actions">
          <button type="button" disabled={!asset.assignedToMe} className="sys-btn sys-btn--text sys-btn--sm"><PlusCircle size={14} /> 加入节点引用</button>
        </div>
      </div>
    </article>
  );
}

function MyAssetCard({ asset }: { asset: MyAssetRow }) {
  return (
    <article className="sys-card sys-card--static">
      <div className="sys-card-header">
        <div className="sys-card-avatar sys-card-avatar--cap"><AssetTypeIcon type={asset.assetType} /></div>
        <div className="sys-card-info">
          <div className="sys-card-name">{asset.name}</div>
          <div className="sys-card-code">{asset.code} · {asset.version}</div>
        </div>
        <span className={`sys-status ${asset.status === "draft" ? "sys-status--inactive" : "sys-status--active"}`}>
          <span className="sys-status-dot" />
          {formatStatus(asset.status)}
        </span>
      </div>
      <div className="sys-info-tags">
        <span className="sys-info-tag sys-info-tag--primary">{formatAssetType(asset.assetType)}</span>
        <RiskTag level={asset.riskLevel} />
        <span className="sys-info-tag">{asset.visibility === "tenant" ? "租户内复用" : "仅自己维护"}</span>
      </div>
      <p className="agent-muted min-h-12 text-sm leading-6">{asset.description || "暂无说明"}</p>
      <div className="sys-card-footer">
        <span className="sys-card-footer-time"><Clock size={12} /> {asset.sourceType === "derived" ? "系统能力派生" : "自建能力"}</span>
        <div className="sys-card-footer-actions">
          <button type="button" className="sys-btn sys-btn--text sys-btn--sm"><Eye size={14} /> 查看详情</button>
        </div>
      </div>
    </article>
  );
}

function SideList({ title, empty, items }: { title: string; empty: string; items: string[] }) {
  return (
    <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
      <h3 className="text-sm font-semibold">{title}</h3>
      <div className="mt-3 space-y-2">
        {items.length === 0 ? (
          <p className="agent-muted text-sm">{empty}</p>
        ) : (
          items.map((item) => <p key={item} className="text-sm text-[var(--color-text-secondary)]">{item}</p>)
        )}
      </div>
    </div>
  );
}

function AssetTypeIcon({ type }: { type: string }) {
  const Icon = type === "agent_template"
    ? Bot
    : type === "mcp"
      ? Boxes
      : type === "prompt_template"
        ? FileText
        : type === "delivery"
          ? CheckCircle2
          : BrainCircuit;
  return <Icon size={22} />;
}

function AssetPagination({ pageState, onChange }: { pageState: PageState; onChange: (page: number, size: number) => void }) {
  if (pageState.total <= 0) return null;
  return (
    <div className="agent-admin-pagination-wrap px-4 py-4">
      <Pagination
        className="agent-admin-pagination"
        current={pageState.page}
        pageSize={pageState.size}
        total={pageState.total}
        locale={paginationLocale}
        showSizeChanger={{ className: "agent-admin-select", popupClassName: "agent-select-dropdown agent-admin-select-dropdown" }}
        pageSizeOptions={["10", "20", "50"]}
        showTotal={(total, range) => `当前 ${range[0]}-${range[1]} 条，共 ${total} 条`}
        onChange={onChange}
        onShowSizeChange={onChange}
      />
    </div>
  );
}

function RiskTag({ level }: { level: string }) {
  const cls = level === "high" ? "sys-info-tag--danger" : level === "medium" ? "sys-info-tag--warn" : "sys-info-tag--success";
  return <span className={`sys-info-tag ${cls}`}>{formatRisk(level)}</span>;
}

function formatAssetType(type: string): string {
  return assetTypeOptions.find((item) => item.value === type)?.label ?? type;
}

function formatRisk(level: string): string {
  if (level === "high") return "高风险";
  if (level === "medium") return "中风险";
  if (level === "low") return "低风险";
  return level;
}

function formatStatus(status: string): string {
  if (status === "draft") return "草稿";
  if (status === "published") return "已发布";
  if (status === "active") return "启用";
  if (status === "disabled") return "停用";
  return status;
}
