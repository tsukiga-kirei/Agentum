import { useCallback, useEffect, useMemo, useState } from "react";
import { ArrowRight, Bot, Boxes, BrainCircuit, CheckCircle2, ChevronDown, CircleAlert, Clock, Edit3, Eye, FileText, Hash, Library, PlusCircle, Search, Send, ShieldCheck, Tag, Trash2, UserRoundCog, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Empty, Pagination, Segmented, Select, Spin, message, Drawer } from "antd";
import { AgentumApiError, assetApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AssetSummary, AssetType, CreatableAssetType, CreateMyAssetRequest, MyAssetDetail, MyAssetRow, SystemCapabilityAssetRow, UpdateMyAssetRequest } from "../../types/asset";

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

const creatableAssetTypeOptions: Array<{ value: CreatableAssetType; label: string }> = [
  { value: "agent_template", label: "智能体模板" },
  { value: "prompt_template", label: "提示词模板" },
];

const allAssetTypeOptions: Array<{ value: AssetType; label: string }> = [
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
  { key: "system", label: "对我开放", icon: Boxes, description: "租户管理已分配给当前用户、部门或角色的系统能力，可作为智能体模板的构建材料" },
  { key: "mine", label: "我的能力", icon: Bot, description: "管理我创建的提示词模板草稿、智能体模板草稿和已发布能力" },
];

const adminSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const adminSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

export function AssetsPage() {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const themeMode = useAuthStore((s) => s.themeMode);
  const [messageApi, messageContextHolder] = message.useMessage();
  const drawerRootClassName = themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer";

  const [systemDetailOpen, setSystemDetailOpen] = useState(false);
  const [selectedSystemAsset, setSelectedSystemAsset] = useState<SystemCapabilityAssetRow | null>(null);

  const [activeTab, setActiveTab] = useState<AssetTab>("overview");
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<AssetSummary | null>(null);
  const [systemAssets, setSystemAssets] = useState<SystemCapabilityAssetRow[]>([]);
  const [myAssets, setMyAssets] = useState<MyAssetRow[]>([]);
  const [systemPage, setSystemPage] = useState<PageState>(defaultPageState);
  const [minePage, setMinePage] = useState<PageState>(defaultPageState);
  const [keyword, setKeyword] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [currentAsset, setCurrentAsset] = useState<MyAssetDetail | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<MyAssetRow | MyAssetDetail | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [draft, setDraft] = useState<CreateMyAssetRequest>({
    assetType: "prompt_template",
    name: "",
    code: "",
    version: "v1",
    description: "",
    riskLevel: "low",
    visibility: "private",
    config: { promptContent: "" },
  });
  const [editDraft, setEditDraft] = useState<UpdateMyAssetRequest>({
    name: "",
    code: "",
    version: "v1",
    description: "",
    riskLevel: "low",
    visibility: "private",
    config: {},
  });

  const tenantId = user?.tenantId ?? "";
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
  const skillOptions = useMemo(
    () => assignedSystemAssets.filter((asset) => asset.assetType === "skill").map((asset) => ({ value: asset.id, label: `${asset.name} · ${asset.version}` })),
    [assignedSystemAssets],
  );
  const mcpOptions = useMemo(
    () => assignedSystemAssets.filter((asset) => asset.assetType === "mcp").map((asset) => ({ value: asset.id, label: `${asset.name} · ${asset.version}` })),
    [assignedSystemAssets],
  );

  const handleCreate = async () => {
    if (!token || !tenantId) {
      messageApi.warning("请先进入有效租户后再创建能力草稿");
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
      messageApi.success("能力草稿已创建");
      setCreateOpen(false);
      setDraft({ assetType: "prompt_template", name: "", code: "", version: "v1", description: "", riskLevel: "low", visibility: "private", config: { promptContent: "" } });
      await Promise.all([loadSummary(), loadMyAssets(1, minePage.size, keyword)]);
      setActiveTab("mine");
    } catch (error) {
      handleApiError(error, "创建能力草稿失败");
    } finally {
      setSubmitting(false);
    }
  };

  const openEdit = async (assetId: string) => {
    if (!token || !tenantId) return;
    setSubmitting(true);
    try {
      const detail = await assetApi.getMine(tenantId, token, assetId);
      setCurrentAsset(detail);
      setEditDraft({
        name: detail.name,
        code: detail.code,
        version: detail.version,
        description: detail.description,
        riskLevel: detail.riskLevel,
        visibility: detail.visibility,
        config: detail.config ?? {},
      });
      setEditOpen(true);
    } catch (error) {
      handleApiError(error, "加载能力草稿失败");
    } finally {
      setSubmitting(false);
    }
  };

  const handleUpdate = async () => {
    if (!token || !tenantId || !currentAsset) return;
    if (!editDraft.name.trim() || !editDraft.code.trim()) {
      messageApi.warning("请输入能力名称和编码");
      return;
    }

    setSubmitting(true);
    try {
      const updated = await assetApi.updateMine(tenantId, token, currentAsset.id, normalizeEditDraft(editDraft, currentAsset.assetType));
      setCurrentAsset(updated);
      messageApi.success("能力草稿已保存");
      await Promise.all([loadSummary(), loadMyAssets(minePage.page, minePage.size, keyword)]);
    } catch (error) {
      handleApiError(error, "保存能力草稿失败");
    } finally {
      setSubmitting(false);
    }
  };

  const handlePublish = async () => {
    if (!token || !tenantId || !currentAsset) return;
    setSubmitting(true);
    try {
      await assetApi.updateMine(tenantId, token, currentAsset.id, normalizeEditDraft(editDraft, currentAsset.assetType));
      const published = await assetApi.publishMine(tenantId, token, currentAsset.id);
      setCurrentAsset(published);
      setEditOpen(false);
      messageApi.success("能力已发布");
      await Promise.all([loadSummary(), loadMyAssets(1, minePage.size, keyword)]);
    } catch (error) {
      handleApiError(error, "发布能力失败");
    } finally {
      setSubmitting(false);
    }
  };

  const deleteAsset = async (asset: MyAssetRow | MyAssetDetail) => {
    if (!token || !tenantId) return;
    setSubmitting(true);
    try {
      await assetApi.deleteMine(tenantId, token, asset.id);
      messageApi.success("能力已删除");
      setEditOpen(false);
      setDeleteTarget(null);
      if (currentAsset?.id === asset.id) {
        setCurrentAsset(null);
      }
      await Promise.all([loadSummary(), loadMyAssets(1, minePage.size, keyword)]);
    } catch (error) {
      handleApiError(error, "删除能力失败");
    } finally {
      setSubmitting(false);
    }
  };

  const confirmDelete = (asset: MyAssetRow | MyAssetDetail) => {
    setDeleteTarget(asset);
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
      {deleteTarget ? (
        <div className="sys-modal-mask agent-delete-confirm-mask" onClick={() => !submitting && setDeleteTarget(null)}>
          <div className="sys-modal agent-delete-confirm-modal" role="dialog" aria-modal="true" aria-labelledby="delete-my-asset-title" onClick={(event) => event.stopPropagation()}>
            <div className="agent-delete-confirm-body">
              <div className="agent-delete-confirm-icon">
                <CircleAlert size={28} aria-hidden="true" />
              </div>
              <div className="agent-delete-confirm-content">
                <h2 id="delete-my-asset-title">删除我的能力</h2>
                <p>确认删除“{deleteTarget.name}”？后续接入流程引用校验后，已被使用的能力将禁止删除。</p>
              </div>
            </div>
            <div className="agent-delete-confirm-footer">
              <button type="button" className="sys-btn sys-btn--default" disabled={submitting} onClick={() => setDeleteTarget(null)}>
                取消
              </button>
              <button type="button" className="sys-btn sys-btn--danger" disabled={submitting} onClick={() => void deleteAsset(deleteTarget)}>
                删除
              </button>
            </div>
          </div>
        </div>
      ) : null}
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
            <OverviewPanel
              summary={summary}
              systemAssets={assignedSystemAssets}
              myAssets={myAssets}
              draftAssets={draftAssets}
              onOpenSystem={() => setActiveTab("system")}
              onOpenMine={() => setActiveTab("mine")}
              onCreateDraft={() => setCreateOpen(true)}
            />
          ) : null}

          {activeTab === "system" ? (
            <section className="sys-fade-in">
              {systemAssets.length === 0 ? (
                <div className="sys-preview-card">
                  <Empty description="当前暂无对我开放的能力" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                </div>
              ) : (
                <div className="sys-card-grid">
                  {systemAssets.map((asset) => (
                    <SystemAssetCard
                      key={asset.id}
                      asset={asset}
                      onView={(a) => {
                        setSelectedSystemAsset(a);
                        setSystemDetailOpen(true);
                      }}
                    />
                  ))}
                </div>
              )}
              <AssetPagination pageState={systemPage} onChange={(page, size) => void loadSystemAssets(page, size)} />
            </section>
          ) : null}

          {activeTab === "mine" ? (
            <section className="sys-fade-in">
              <div style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", marginBottom: 20 }}>
                <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
                  <div className="sys-field-input-wrap min-w-[260px]" style={{ height: 36, minHeight: 36 }}>
                    <Search size={16} className="sys-field-prefix" aria-hidden="true" style={{ marginTop: 0 }} />
                    <input
                      className="sys-field-input"
                      style={{ height: 36, minHeight: 36, paddingTop: 0, paddingBottom: 0 }}
                      value={keyword}
                      placeholder="搜索名称、编码或说明"
                      onChange={(event) => setKeyword(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") void loadMyAssets(1, minePage.size, keyword);
                      }}
                    />
                  </div>
                  <button type="button" className="sys-btn sys-btn--default" onClick={() => void loadMyAssets(1, minePage.size, keyword)}>
                    <Search size={14} />
                    查询
                  </button>
                  <button type="button" className="sys-btn sys-btn--primary" onClick={() => setCreateOpen(true)}>
                    <PlusCircle size={14} />
                    新建能力草稿
                  </button>
                </div>
              </div>
              {myAssets.length === 0 ? (
                <div className="sys-preview-card">
                  <Empty description="还没有我的能力" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                </div>
              ) : (
                <div className="sys-card-grid">
                  {myAssets.map((asset) => <MyAssetCard key={asset.id} asset={asset} onEdit={openEdit} onDelete={confirmDelete} />)}
                </div>
              )}
              <AssetPagination pageState={minePage} onChange={(page, size) => void loadMyAssets(page, size, keyword)} />
            </section>
          ) : null}
        </Spin>
      </div>

      {/* 新建能力草稿抽屉 */}
      <Drawer
        title="新建能力草稿"
        placement="right"
        width={560}
        onClose={() => setCreateOpen(false)}
        open={createOpen}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section">
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">能力类型</label>
            <Select
              className="agent-admin-select w-full"
              classNames={adminSelectClassNames}
              prefix={<Boxes className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
              suffixIcon={adminSelectSuffixIcon}
              value={draft.assetType}
              options={creatableAssetTypeOptions}
              onChange={(assetType) => setDraft((current) => ({ ...current, assetType, config: assetType === "prompt_template" ? { promptContent: "" } : { systemPrompt: "", skillIds: [], mcpIds: [] } }))}
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
        </div>
        <div className="sys-drawer-footer">
          <div className="sys-drawer-footer-right">
            <button type="button" className="sys-btn sys-btn--default" onClick={() => setCreateOpen(false)}>
              <X size={14} />
              取消
            </button>
            <button type="button" className="sys-btn sys-btn--primary" disabled={submitting} onClick={() => void handleCreate()}>
              <PlusCircle size={14} />
              创建草稿
            </button>
          </div>
        </div>
      </Drawer>

      {/* 编辑能力抽屉 */}
      <Drawer
        title={currentAsset?.status === "draft" ? "编辑能力草稿" : "编辑正式能力"}
        placement="right"
        width={560}
        onClose={() => setEditOpen(false)}
        open={editOpen}
        rootClassName={drawerRootClassName}
      >
        {currentAsset ? (
          <>
            <div className="sys-drawer-section">
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">能力名称</label>
                  <div className="sys-field-input-wrap">
                    <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input className="sys-field-input" value={editDraft.name} onChange={(event) => setEditDraft((current) => ({ ...current, name: event.target.value }))} />
                  </div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">能力编码</label>
                  <div className="sys-field-input-wrap">
                    <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input className="sys-field-input" value={editDraft.code} onChange={(event) => setEditDraft((current) => ({ ...current, code: event.target.value }))} />
                  </div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">版本</label>
                  <div className="sys-field-input-wrap">
                    <FileText size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input className="sys-field-input" value={editDraft.version ?? "v1"} onChange={(event) => setEditDraft((current) => ({ ...current, version: event.target.value }))} />
                  </div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">风险等级</label>
                  <Select
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<ShieldCheck className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    value={editDraft.riskLevel}
                    options={riskOptions}
                    onChange={(riskLevel) => setEditDraft((current) => ({ ...current, riskLevel }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">说明</label>
                <textarea className="sys-field-textarea" value={editDraft.description ?? ""} onChange={(event) => setEditDraft((current) => ({ ...current, description: event.target.value }))} />
              </div>

              {currentAsset.assetType === "prompt_template" ? (
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">提示词内容</label>
                  <textarea
                    className="sys-field-textarea min-h-[220px]"
                    value={getConfigString(editDraft.config, "promptContent")}
                    onChange={(event) => setEditDraft((current) => ({ ...current, config: { ...(current.config ?? {}), promptContent: event.target.value } }))}
                  />
                </div>
              ) : null}

              {currentAsset.assetType === "agent_template" ? (
                <>
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">系统提示词</label>
                    <textarea
                      className="sys-field-textarea min-h-[180px]"
                      value={getConfigString(editDraft.config, "systemPrompt")}
                      onChange={(event) => setEditDraft((current) => ({ ...current, config: { ...(current.config ?? {}), systemPrompt: event.target.value } }))}
                    />
                  </div>
                  <div className="sys-field-row">
                    <div className="sys-field">
                      <label className="sys-field-label">可用 Skill</label>
                      <Select
                        mode="multiple"
                        className="agent-admin-select w-full"
                        classNames={adminSelectClassNames}
                        prefix={<BrainCircuit className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                        suffixIcon={adminSelectSuffixIcon}
                        value={getConfigIds(editDraft.config, "skillIds")}
                        options={skillOptions}
                        onChange={(skillIds) => setEditDraft((current) => ({ ...current, config: { ...(current.config ?? {}), skillIds } }))}
                      />
                    </div>
                    <div className="sys-field">
                      <label className="sys-field-label">可用 MCP</label>
                      <Select
                        mode="multiple"
                        className="agent-admin-select w-full"
                        classNames={adminSelectClassNames}
                        prefix={<Boxes className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                        suffixIcon={adminSelectSuffixIcon}
                        value={getConfigIds(editDraft.config, "mcpIds")}
                        options={mcpOptions}
                        onChange={(mcpIds) => setEditDraft((current) => ({ ...current, config: { ...(current.config ?? {}), mcpIds } }))}
                      />
                    </div>
                  </div>
                </>
              ) : null}
            </div>

            <div className="sys-drawer-footer">
              {currentAsset.status === "draft" ? (
                <>
                  <button type="button" className="sys-btn sys-btn--danger" style={{ marginRight: "auto" }} disabled={submitting} onClick={() => confirmDelete(currentAsset)}>
                    <Trash2 size={14} />
                    删除
                  </button>
                  <div className="sys-drawer-footer-right">
                    <button type="button" className="sys-btn sys-btn--default" onClick={() => setEditOpen(false)}>
                      <X size={14} />
                      取消
                    </button>
                    <button type="button" className="sys-btn sys-btn--default" disabled={submitting} onClick={() => void handleUpdate()}>
                      <Edit3 size={14} />
                      保存草稿
                    </button>
                    <button type="button" className="sys-btn sys-btn--primary" disabled={submitting} onClick={() => void handlePublish()}>
                      <Send size={14} />
                      发布能力
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <button type="button" className="sys-btn sys-btn--danger" style={{ marginRight: "auto" }} disabled={submitting} onClick={() => confirmDelete(currentAsset)}>
                    <Trash2 size={14} />
                    删除
                  </button>
                  <div className="sys-drawer-footer-right">
                    <button type="button" className="sys-btn sys-btn--default" onClick={() => setEditOpen(false)}>
                      <X size={14} />
                      取消
                    </button>
                    <button type="button" className="sys-btn sys-btn--primary" disabled={submitting} onClick={() => void handleUpdate()}>
                      <Edit3 size={14} />
                      保存修改
                    </button>
                  </div>
                </>
              )}
            </div>
          </>
        ) : null}
      </Drawer>

      {/* 系统能力详情抽屉 */}
      <Drawer
        title="系统能力详情"
        placement="right"
        width={560}
        onClose={() => { setSystemDetailOpen(false); setSelectedSystemAsset(null); }}
        open={systemDetailOpen}
        rootClassName={drawerRootClassName}
      >
        {selectedSystemAsset ? (
          <div className="sys-drawer-section">
            <div className="sys-field">
              <label className="sys-field-label">能力名称</label>
              <div className="sys-field-input-wrap">
                <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
                <input className="sys-field-input" disabled value={selectedSystemAsset.name} />
              </div>
            </div>
            <div className="sys-field">
              <label className="sys-field-label">能力编码</label>
              <div className="sys-field-input-wrap">
                <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
                <input className="sys-field-input" disabled value={selectedSystemAsset.code} />
              </div>
            </div>
            <div className="sys-field-row">
              <div className="sys-field">
                <label className="sys-field-label">能力类型</label>
                <div className="sys-field-input-wrap">
                  <Boxes size={16} className="sys-field-prefix" aria-hidden="true" />
                  <input className="sys-field-input" disabled value={formatAssetType(selectedSystemAsset.assetType)} />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">版本</label>
                <div className="sys-field-input-wrap">
                  <FileText size={16} className="sys-field-prefix" aria-hidden="true" />
                  <input className="sys-field-input" disabled value={selectedSystemAsset.version} />
                </div>
              </div>
            </div>
            <div className="sys-field-row">
              <div className="sys-field">
                <label className="sys-field-label">风险等级</label>
                <div className="sys-field-input-wrap">
                  <ShieldCheck size={16} className="sys-field-prefix" aria-hidden="true" />
                  <input className="sys-field-input" disabled value={formatRisk(selectedSystemAsset.riskLevel)} />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">分配状态</label>
                <div className="sys-field-input-wrap">
                  <UserRoundCog size={16} className="sys-field-prefix" aria-hidden="true" />
                  <input className="sys-field-input" disabled value={selectedSystemAsset.assignmentScope} />
                </div>
              </div>
            </div>
            <div className="sys-field">
              <label className="sys-field-label">说明</label>
              <textarea className="sys-field-textarea" disabled value="系统开放的通用能力，可用于编排智能体模板或在流程节点中进行引用。" />
            </div>
          </div>
        ) : null}
        <div className="sys-drawer-footer">
          <div className="sys-drawer-footer-right">
            <button type="button" className="sys-btn sys-btn--default" onClick={() => { setSystemDetailOpen(false); setSelectedSystemAsset(null); }}>
              <X size={14} />
              关闭
            </button>
          </div>
        </div>
      </Drawer>
    </div>
  );
}

function OverviewPanel({
  summary,
  systemAssets,
  myAssets,
  draftAssets,
  onOpenSystem,
  onOpenMine,
  onCreateDraft,
}: {
  summary: AssetSummary | null;
  systemAssets: SystemCapabilityAssetRow[];
  myAssets: MyAssetRow[];
  draftAssets: MyAssetRow[];
  onOpenSystem: () => void;
  onOpenMine: () => void;
  onCreateDraft: () => void;
}) {
  return (
    <div className="sys-fade-in">
      <div className="sys-overview-stats">
        <OverviewStat icon={CheckCircle2} value={summary?.openedToMeSystemTotal ?? 0} label="对我开放能力" tone="success" />
        <OverviewStat icon={Boxes} value={summary?.tenantSystemPoolTotal ?? 0} label="租户能力池" tone="primary" />
        <OverviewStat icon={Library} value={summary?.myAssetTotal ?? 0} label="我的能力" tone="cap" />
        <OverviewStat icon={BrainCircuit} value={draftAssets.length} label="待完善草稿" tone="info" />
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <section className="sys-preview-card">
          <div className="sys-preview-card-title"><ShieldCheck size={16} /> 能力功能入口</div>
          <div className="grid gap-3 lg:grid-cols-2">
            <AssetFeatureCard icon={Boxes} title="对我开放" detail="查看当前用户、部门或角色已被分配的系统能力。" meta={`${systemAssets.length} 项当前页可引用能力`} onClick={onOpenSystem} />
            <AssetFeatureCard icon={Library} title="我的能力" detail="维护我创建的提示词模板和智能体模板草稿。" meta={`${summary?.myAssetTotal ?? myAssets.length} 项我的能力`} onClick={onOpenMine} />
            <AssetFeatureCard icon={PlusCircle} title="新建能力草稿" detail="创建提示词模板或智能体模板，再通过发布进入复用链路。" meta="提示词模板 / 智能体模板" onClick={onCreateDraft} />
            <AssetFeatureCard icon={BrainCircuit} title="待完善草稿" detail="回到我的能力处理未发布草稿和版本说明。" meta={`${draftAssets.length} 项待完善`} onClick={onOpenMine} />
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

function AssetFeatureCard({
  icon: Icon,
  title,
  detail,
  meta,
  onClick,
}: {
  icon: LucideIcon;
  title: string;
  detail: string;
  meta: string;
  onClick: () => void;
}) {
  return (
    <button type="button" className="asset-feature-card" onClick={onClick}>
      <div className="flex items-center gap-2">
        <span className="asset-feature-card-icon sys-preview-item-icon sys-card-avatar--cap"><Icon size={16} /></span>
        <h3 className="asset-feature-card-title text-sm font-semibold">{title}</h3>
      </div>
      <p className="asset-feature-card-detail agent-muted mt-3 text-sm leading-6">{detail}</p>
      <span className="asset-feature-card-meta">
        {meta}
        <ArrowRight size={14} aria-hidden="true" />
      </span>
    </button>
  );
}

function SystemAssetCard({ asset, onView }: { asset: SystemCapabilityAssetRow; onView: (asset: SystemCapabilityAssetRow) => void }) {
  return (
    <article className="sys-card" onClick={() => onView(asset)}>
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
        <div className="sys-card-footer-actions" onClick={(e) => e.stopPropagation()}>
          <button type="button" disabled={!asset.assignedToMe} className="sys-btn sys-btn--text sys-btn--sm"><PlusCircle size={14} /> 加入节点引用</button>
        </div>
      </div>
    </article>
  );
}

function MyAssetCard({ asset, onEdit, onDelete }: { asset: MyAssetRow; onEdit: (assetId: string) => void; onDelete: (asset: MyAssetRow) => void }) {
  return (
    <article className="sys-card" onClick={() => onEdit(asset.id)}>
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
        <span className="sys-card-footer-time"><Clock size={12} /> {asset.sourceType === "derived" ? "系统能力派生" : "我的能力"}</span>
        <div className="sys-card-footer-actions" onClick={(e) => e.stopPropagation()}>
          <button type="button" className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onEdit(asset.id)}>
            {asset.status === "draft" ? <Edit3 size={14} /> : <Eye size={14} />}
            {asset.status === "draft" ? "编辑草稿" : "查看详情"}
          </button>
          <button type="button" className="sys-btn sys-btn--text sys-btn--danger-text sys-btn--sm" onClick={() => onDelete(asset)}>
            <Trash2 size={14} />
            删除
          </button>
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

function normalizeEditDraft(draft: UpdateMyAssetRequest, assetType: AssetType): UpdateMyAssetRequest {
  const base = {
    ...draft,
    name: draft.name.trim(),
    code: draft.code.trim(),
    version: draft.version?.trim() || "v1",
    description: draft.description?.trim(),
  };
  if (assetType === "prompt_template") {
    return { ...base, config: { promptContent: getConfigString(draft.config, "promptContent") } };
  }
  if (assetType === "agent_template") {
    return {
      ...base,
      config: {
        systemPrompt: getConfigString(draft.config, "systemPrompt"),
        skillIds: getConfigIds(draft.config, "skillIds"),
        mcpIds: getConfigIds(draft.config, "mcpIds"),
      },
    };
  }
  return base;
}

function getConfigString(config: Record<string, unknown> | undefined, key: string): string {
  const value = config?.[key];
  return typeof value === "string" ? value : "";
}

function getConfigIds(config: Record<string, unknown> | undefined, key: string): string[] {
  const value = config?.[key];
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function formatAssetType(type: string): string {
  return allAssetTypeOptions.find((item) => item.value === type)?.label ?? type;
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
