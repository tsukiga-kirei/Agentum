import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { ArrowRight, Bot, Boxes, BrainCircuit, CheckCircle2, ChevronDown, CircleAlert, Clock, Edit3, Eye, FileText, Hash, Library, PlusCircle, RotateCcw, Search, Send, ShieldCheck, Tag, Trash2, UserRoundCog, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Empty, Pagination, Segmented, Select, Spin, message, Drawer } from "antd";
import { SurfacePageLayout } from "../../components/workbench/SurfacePageLayout";
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

const defaultAgentConfig = (): Record<string, unknown> => ({
  systemPrompt: "",
  systemPromptTemplateId: "none",
  skillIds: [],
  mcpIds: [],
});

const assetTabs: Array<{ key: AssetTab; label: string; icon: LucideIcon; description: string }> = [
  { key: "overview", label: "总览", icon: Library, description: "查看系统能力、租户分配和自建资产的整体勾稽" },
  { key: "system", label: "对我开放", icon: Boxes, description: "查看租户管理已分配给当前用户、部门或角色的系统能力及当前版本" },
  { key: "mine", label: "我的能力", icon: Bot, description: "管理我创建的提示词模板草稿、智能体模板草稿和已发布能力" },
];

const adminSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const adminSelectSuffixIcon = <ChevronDown className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />;

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
  const [systemKeyword, setSystemKeyword] = useState("");
  const [systemTypeFilter, setSystemTypeFilter] = useState<AssetType | "all">("all");
  const [mineTypeFilter, setMineTypeFilter] = useState<AssetType | "all">("all");
  const [mineStatusFilter, setMineStatusFilter] = useState<"all" | "draft" | "published">("all");
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [currentAsset, setCurrentAsset] = useState<MyAssetDetail | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<MyAssetRow | MyAssetDetail | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [draft, setDraft] = useState<CreateMyAssetRequest>({
    assetType: "prompt_template",
    name: "",
    version: "v1",
    description: "",
    riskLevel: "low",
    visibility: "private",
    config: { promptContent: "" },
  });
  const [editDraft, setEditDraft] = useState<UpdateMyAssetRequest>({
    name: "",
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
    async (page = systemPage.page, size = systemPage.size, type: string = systemTypeFilter === "all" ? "" : systemTypeFilter, search: string = systemKeyword.trim()) => {
      if (!token || !tenantId) return;
      setLoading(true);
      try {
        const response = await assetApi.listSystemCapabilities(tenantId, token, page, size, "openedAt,desc", type, search);
        setSystemAssets(response.items);
        setSystemPage({ page: response.page, size: response.size, total: response.total });
      } catch (error) {
        handleApiError(error, "加载对我开放能力失败");
      } finally {
        setLoading(false);
      }
    },
    [handleApiError, systemKeyword, systemPage.page, systemPage.size, systemTypeFilter, tenantId, token],
  );

  const loadMyAssets = useCallback(
    async (page = minePage.page, size = minePage.size, search = keyword, type: string = mineTypeFilter === "all" ? "" : mineTypeFilter, statusFilter: string = mineStatusFilter === "all" ? "" : mineStatusFilter) => {
      if (!token || !tenantId) return;
      setLoading(true);
      try {
        const response = await assetApi.listMine(tenantId, token, search, page, size, "updatedAt,desc", type, statusFilter);
        setMyAssets(response.items);
        setMinePage({ page: response.page, size: response.size, total: response.total });
      } catch (error) {
        handleApiError(error, "加载我的能力失败");
      } finally {
        setLoading(false);
      }
    },
    [handleApiError, keyword, minePage.page, minePage.size, mineStatusFilter, mineTypeFilter, tenantId, token],
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
  // 能力类型和关键字过滤已经在服务端执行，前端直接使用服务端返回结果，避免客户端在已分页数据上再次过滤导致显示不一致。
  const filteredSystemAssets = systemAssets;
  const draftAssets = useMemo(() => myAssets.filter((asset) => asset.status === "draft"), [myAssets]);
  const filteredMyAssets = myAssets;
  const skillOptions = useMemo(
    () => assignedSystemAssets.filter((asset) => asset.assetType === "skill").map((asset) => ({ value: asset.id, label: `${asset.name} · ${asset.version}` })),
    [assignedSystemAssets],
  );
  const mcpOptions = useMemo(
    () => assignedSystemAssets.filter((asset) => asset.assetType === "mcp").map((asset) => ({ value: asset.id, label: `${asset.name} · ${asset.version}` })),
    [assignedSystemAssets],
  );
  const promptTemplateOptions = useMemo(() => {
    const systemOptions = assignedSystemAssets
      .filter((asset) => asset.assetType === "prompt_template")
      .map((asset) => ({ value: asset.id, label: `${asset.name} · ${asset.version}（对我开放）` }));
    const myOptions = myAssets
      .filter((asset) => asset.assetType === "prompt_template" && asset.status === "published")
      .map((asset) => ({
        value: asset.id,
        label: `${asset.name} · ${asset.version}（我的能力·已发布）`,
      }));
    return [{ value: "none", label: "自定义系统提示词" }, ...systemOptions, ...myOptions];
  }, [assignedSystemAssets, myAssets]);

  const resolvePromptTemplateContent = useCallback(
    async (templateId: string) => {
      if (templateId === "none" || !templateId) {
        return "";
      }
      const systemAsset = assignedSystemAssets.find((asset) => asset.id === templateId);
      if (systemAsset?.promptContent) {
        return systemAsset.promptContent;
      }
      const mineAsset = myAssets.find((asset) => asset.id === templateId);
      if (!mineAsset || !token || !tenantId) {
        return "";
      }
      try {
        const detail = await assetApi.getMine(tenantId, token, mineAsset.id);
        return getConfigString(detail.config, "promptContent");
      } catch (error) {
        console.warn("[assets] 加载提示词模板正文失败", { templateId, tenantId });
        return "";
      }
    },
    [assignedSystemAssets, myAssets, tenantId, token],
  );

  const applyPromptTemplateSelection = useCallback(
    async (config: Record<string, unknown> | undefined, templateId: string) => {
      const nextConfig = { ...(config ?? {}), systemPromptTemplateId: templateId };
      if (templateId === "none") {
        return nextConfig;
      }
      const promptContent = await resolvePromptTemplateContent(templateId);
      if (promptContent) {
        nextConfig.systemPrompt = promptContent;
      }
      return nextConfig;
    },
    [resolvePromptTemplateContent],
  );

  const handleCreate = async () => {
    if (!token || !tenantId) {
      messageApi.warning("请先进入有效租户后再创建能力草稿");
      return;
    }
    if (!draft.name.trim()) {
      messageApi.warning("请输入能力名称");
      return;
    }
    if (draft.assetType === "prompt_template" && !getConfigString(draft.config, "promptContent").trim()) {
      messageApi.warning("请输入提示词内容");
      return;
    }

    setSubmitting(true);
    try {
      await assetApi.createMine(tenantId, token, {
        ...draft,
        name: draft.name.trim(),
        version: draft.version?.trim() || "v1",
        description: draft.description?.trim(),
      });
      messageApi.success("能力草稿已创建");
      setCreateOpen(false);
      setDraft({ assetType: "prompt_template", name: "", version: "v1", description: "", riskLevel: "low", visibility: "private", config: { promptContent: "" } });
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
    if (!editDraft.name.trim()) {
      messageApi.warning("请输入能力名称");
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

  const handleRevertToDraft = async () => {
    if (!token || !tenantId || !currentAsset) return;
    setSubmitting(true);
    try {
      const reverted = await assetApi.revertMineToDraft(tenantId, token, currentAsset.id);
      setCurrentAsset(reverted);
      setEditDraft({
        name: reverted.name,
        version: reverted.version,
        description: reverted.description,
        riskLevel: reverted.riskLevel,
        visibility: reverted.visibility,
        config: reverted.config ?? {},
      });
      messageApi.success("已改回草稿，可继续编辑");
      await Promise.all([loadSummary(), loadMyAssets(minePage.page, minePage.size, keyword)]);
    } catch (error) {
      handleApiError(error, "改回草稿失败");
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
      <SurfacePageLayout
        markClassName="asset-mgmt-page-mark"
        icon={Library}
        title="能力资产"
        badge="业务能力治理"
        description="能力资产需要明确租户上下文，用于连接系统能力池、租户分配和流程节点引用。"
      >
        <Empty description="系统管理入口不绑定租户，请切换到业务用户或租户管理角色后查看能力资产" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </SurfacePageLayout>
    );
  }

  return (
    <>
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
      <SurfacePageLayout
        markClassName="asset-mgmt-page-mark"
        icon={Library}
        title="能力资产"
        badge="业务能力治理"
        description="连接系统管理开放能力、租户管理分配与业务侧自建能力，查看当前主体可用能力及版本信息。"
      >
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
              onOpenDrafts={() => {
                setMineStatusFilter("draft");
                setActiveTab("mine");
              }}
              onCreateDraft={() => setCreateOpen(true)}
              onOpenSystemAsset={(asset) => {
                setSelectedSystemAsset(asset);
                setSystemDetailOpen(true);
              }}
              onOpenMyAsset={(asset) => void openEdit(asset.id)}
            />
          ) : null}

          {activeTab === "system" ? (
            <section className="sys-fade-in">
              <AssetFilterBar>
                <div className="sys-field-input-wrap asset-filter-search min-w-[280px]">
                  <Search size={18} className="sys-field-prefix" aria-hidden="true" />
                  <input
                    className="sys-field-input"
                    value={systemKeyword}
                    placeholder="搜索名称、编码或分配范围"
                    onChange={(event) => setSystemKeyword(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") void loadSystemAssets(1, systemPage.size, systemTypeFilter === "all" ? "" : systemTypeFilter, event.currentTarget.value.trim());
                    }}
                  />
                </div>
                <Select
                  className="agent-admin-select min-w-[180px]"
                  classNames={adminSelectClassNames}
                  prefix={<Boxes className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                  suffixIcon={adminSelectSuffixIcon}
                  value={systemTypeFilter}
                  options={[{ value: "all", label: "全部能力类型" }, ...allAssetTypeOptions]}
                  onChange={(value) => {
                    setSystemTypeFilter(value as AssetType | "all");
                    void loadSystemAssets(1, systemPage.size, value === "all" ? "" : value, systemKeyword.trim());
                  }}
                />
              </AssetFilterBar>
              {filteredSystemAssets.length === 0 ? (
                <div className="workflow-definition-empty-state">
                  <Search className="h-8 w-8 shrink-0" aria-hidden="true" />
                  <p>当前暂无匹配的开放能力</p>
                  <span>可以调整搜索词或能力类型筛选条件。</span>
                </div>
              ) : (
                <div className="sys-card-grid">
                  {filteredSystemAssets.map((asset) => (
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
              <AssetFilterBar>
                  <div className="sys-field-input-wrap asset-filter-search min-w-[260px]">
                    <Search size={18} className="sys-field-prefix" aria-hidden="true" />
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
                  <Select
                    className="agent-admin-select min-w-[170px]"
                    classNames={adminSelectClassNames}
                    prefix={<Boxes className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    value={mineTypeFilter}
                    options={[{ value: "all", label: "全部能力类型" }, ...allAssetTypeOptions.filter((item) => item.value === "agent_template" || item.value === "prompt_template")]}
                    onChange={(value) => {
                      setMineTypeFilter(value as AssetType | "all");
                      void loadMyAssets(1, minePage.size, keyword, value === "all" ? "" : value, mineStatusFilter === "all" ? "" : mineStatusFilter);
                    }}
                  />
                  <Select
                    className="agent-admin-select min-w-[150px]"
                    classNames={adminSelectClassNames}
                    prefix={<ShieldCheck className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    value={mineStatusFilter}
                    options={[
                      { value: "all", label: "全部状态" },
                      { value: "draft", label: "草稿" },
                      { value: "published", label: "已发布" },
                    ]}
                    onChange={(value) => {
                      setMineStatusFilter(value as "all" | "draft" | "published");
                      void loadMyAssets(1, minePage.size, keyword, mineTypeFilter === "all" ? "" : mineTypeFilter, value === "all" ? "" : value);
                    }}
                  />
                  <button type="button" className="sys-btn sys-btn--default" onClick={() => void loadMyAssets(1, minePage.size, keyword)}>
                    <Search size={18} />
                    查询
                  </button>
                  <button type="button" className="sys-btn sys-btn--primary" onClick={() => setCreateOpen(true)}>
                    <PlusCircle size={18} />
                    新建能力草稿
                  </button>
              </AssetFilterBar>
              {filteredMyAssets.length === 0 ? (
                <div className="workflow-definition-empty-state">
                  <Search className="h-8 w-8 shrink-0" aria-hidden="true" />
                  <p>当前暂无匹配的我的能力</p>
                  <span>可以调整搜索词、类型或状态筛选条件。</span>
                </div>
              ) : (
                <div className="sys-card-grid">
                  {filteredMyAssets.map((asset) => <MyAssetCard key={asset.id} asset={asset} onEdit={openEdit} onDelete={confirmDelete} />)}
                </div>
              )}
              <AssetPagination pageState={minePage} onChange={(page, size) => void loadMyAssets(page, size, keyword)} />
            </section>
          ) : null}
        </Spin>
      </SurfacePageLayout>

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
              onChange={(assetType) => setDraft((current) => ({
                ...current,
                assetType,
                config: assetType === "prompt_template" ? { promptContent: "" } : defaultAgentConfig(),
              }))}
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
              <label className="sys-field-label">编码</label>
              <div className="sys-field-input-wrap">
                <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
                <input className="sys-field-input" disabled placeholder="保存后自动生成" readOnly />
              </div>
              <div className="sys-field-hint">根据名称自动生成，同一租户内保证唯一</div>
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
          {draft.assetType === "prompt_template" ? (
            <div className="sys-field">
              <label className="sys-field-label sys-field-label--required">提示词内容</label>
              <textarea
                className="sys-field-textarea min-h-[220px]"
                value={getConfigString(draft.config, "promptContent")}
                placeholder="直接输入提示词正文"
                onChange={(event) => setDraft((current) => ({ ...current, config: { ...(current.config ?? {}), promptContent: event.target.value } }))}
              />
            </div>
          ) : null}
          {draft.assetType === "agent_template" ? (
            <AgentTemplateConfigFields
              config={draft.config}
              promptTemplateOptions={promptTemplateOptions}
              skillOptions={skillOptions}
              mcpOptions={mcpOptions}
              onChange={(config) => setDraft((current) => ({ ...current, config }))}
              onPromptTemplateChange={(templateId) => {
                void applyPromptTemplateSelection(draft.config, templateId).then((config) => {
                  setDraft((current) => ({ ...current, config }));
                });
              }}
            />
          ) : null}
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
              {currentAsset.status === "published" ? (
                <div className="sys-hint"><ShieldCheck size={14} /> 已发布能力当前为只读查看；如需修改，请先改回草稿。</div>
              ) : null}
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">能力名称</label>
                  <div className="sys-field-input-wrap">
                    <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input className="sys-field-input" disabled={currentAsset.status !== "draft"} value={editDraft.name} onChange={(event) => setEditDraft((current) => ({ ...current, name: event.target.value }))} />
                  </div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">能力编码</label>
                  <div className="sys-field-input-wrap">
                    <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input className="sys-field-input" disabled value={currentAsset.code} readOnly />
                  </div>
                  <div className="sys-field-hint">编码创建后不可修改</div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">版本</label>
                  <div className="sys-field-input-wrap">
                    <FileText size={16} className="sys-field-prefix" aria-hidden="true" />
                    <input className="sys-field-input" disabled={currentAsset.status !== "draft"} value={editDraft.version ?? "v1"} onChange={(event) => setEditDraft((current) => ({ ...current, version: event.target.value }))} />
                  </div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">风险等级</label>
                  <Select
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<ShieldCheck className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    disabled={currentAsset.status !== "draft"}
                    value={editDraft.riskLevel}
                    options={riskOptions}
                    onChange={(riskLevel) => setEditDraft((current) => ({ ...current, riskLevel }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">说明</label>
                <textarea className="sys-field-textarea" disabled={currentAsset.status !== "draft"} value={editDraft.description ?? ""} onChange={(event) => setEditDraft((current) => ({ ...current, description: event.target.value }))} />
              </div>

              {currentAsset.assetType === "prompt_template" ? (
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">提示词内容</label>
                  <textarea
                    className="sys-field-textarea min-h-[220px]"
                    disabled={currentAsset.status !== "draft"}
                    value={getConfigString(editDraft.config, "promptContent")}
                    onChange={(event) => setEditDraft((current) => ({ ...current, config: { ...(current.config ?? {}), promptContent: event.target.value } }))}
                  />
                </div>
              ) : null}

              {currentAsset.assetType === "agent_template" ? (
                <AgentTemplateConfigFields
                  readOnly={currentAsset.status !== "draft"}
                  config={editDraft.config}
                  promptTemplateOptions={promptTemplateOptions}
                  skillOptions={skillOptions}
                  mcpOptions={mcpOptions}
                  onChange={(config) => setEditDraft((current) => ({ ...current, config }))}
                  onPromptTemplateChange={(templateId) => {
                    void applyPromptTemplateSelection(editDraft.config, templateId).then((config) => {
                      setEditDraft((current) => ({ ...current, config }));
                    });
                  }}
                />
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
                      关闭
                    </button>
                    <button type="button" className="sys-btn sys-btn--primary" disabled={submitting} onClick={() => void handleRevertToDraft()}>
                      <RotateCcw size={14} />
                      改回草稿
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
              <div className="sys-readonly-textarea">{selectedSystemAsset.description || "暂无说明"}</div>
            </div>
            {selectedSystemAsset.assetType === "prompt_template" && selectedSystemAsset.promptContent ? (
              <div className="sys-field">
                <label className="sys-field-label">提示词内容</label>
                <div className="sys-readonly-textarea min-h-[220px]">{selectedSystemAsset.promptContent}</div>
              </div>
            ) : null}
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
    </>
  );
}

function OverviewPanel({
  summary,
  systemAssets,
  myAssets,
  draftAssets,
  onOpenSystem,
  onOpenMine,
  onOpenDrafts,
  onCreateDraft,
  onOpenSystemAsset,
  onOpenMyAsset,
}: {
  summary: AssetSummary | null;
  systemAssets: SystemCapabilityAssetRow[];
  myAssets: MyAssetRow[];
  draftAssets: MyAssetRow[];
  onOpenSystem: () => void;
  onOpenMine: () => void;
  onOpenDrafts: () => void;
  onCreateDraft: () => void;
  onOpenSystemAsset: (asset: SystemCapabilityAssetRow) => void;
  onOpenMyAsset: (asset: MyAssetRow) => void;
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
            <AssetFeatureCard icon={Boxes} title="对我开放" detail="查看当前用户、部门或角色已被分配的系统能力。" meta={`${systemAssets.length} 项当前页已分配能力`} onClick={onOpenSystem} />
            <AssetFeatureCard icon={Library} title="我的能力" detail="维护我创建的提示词模板和智能体模板草稿。" meta={`${summary?.myAssetTotal ?? myAssets.length} 项我的能力`} onClick={onOpenMine} />
            <AssetFeatureCard icon={PlusCircle} title="新建能力草稿" detail="创建提示词模板或智能体模板，再通过发布进入复用链路。" meta="提示词模板 / 智能体模板" onClick={onCreateDraft} />
            <AssetFeatureCard icon={BrainCircuit} title="待完善草稿" detail="回到我的能力处理未发布草稿和版本说明。" meta={`${draftAssets.length} 项待完善`} onClick={onOpenDrafts} />
          </div>
        </section>

        <aside className="sys-preview-card">
          <div className="sys-preview-card-title"><BrainCircuit size={16} /> 近期能力</div>
          <div className="space-y-3">
            <SideList
              title="近期开放的能力"
              empty="暂无开放给我的能力"
            >
              {systemAssets.slice(0, 3).map((asset) => (
                <SystemAssetPreviewItem key={asset.id} asset={asset} onClick={() => onOpenSystemAsset(asset)} />
              ))}
            </SideList>
            <SideList
              title="近期新建的能力"
              empty="暂无最近创建的能力"
            >
              {myAssets.slice(0, 3).map((asset) => (
                <MyAssetPreviewItem key={asset.id} asset={asset} onClick={() => onOpenMyAsset(asset)} />
              ))}
            </SideList>
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
      <p className="agent-muted min-h-12 text-sm leading-6">{asset.description || "暂无说明"}</p>
      <div className="sys-card-meta">
        <div className="sys-meta-item">
          <span className="sys-meta-label">分配范围</span>
          <span className="sys-meta-value">{asset.assignmentScope}</span>
        </div>
        <div className="sys-meta-item">
          <span className="sys-meta-label">当前版本</span>
          <span className="sys-meta-value">{asset.version}</span>
        </div>
      </div>
      <div className="sys-card-footer">
        <span className="sys-card-footer-time"><Clock size={12} /> 系统开放能力</span>
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

function AssetFilterBar({ children }: { children: ReactNode }) {
  return (
    <div className="asset-filter-bar">
      {children}
    </div>
  );
}

function SideList({ title, empty, children }: { title: string; empty: string; children: ReactNode }) {
  const hasChildren = Array.isArray(children) ? children.length > 0 : Boolean(children);
  return (
    <div className="asset-side-list">
      <h3>{title}</h3>
      <div className="mt-3 space-y-2">
        {!hasChildren ? (
          <p className="agent-muted text-sm">{empty}</p>
        ) : (
          children
        )}
      </div>
    </div>
  );
}

function SystemAssetPreviewItem({ asset, onClick }: { asset: SystemCapabilityAssetRow; onClick: () => void }) {
  return (
    <button type="button" className="sys-preview-item asset-preview-item" onClick={onClick}>
        <span className="sys-preview-item-left">
          <span className="sys-preview-item-icon sys-card-avatar--cap">
          <AssetTypeIcon type={asset.assetType} size={16} />
        </span>
        <span className="min-w-0">
          <span className="sys-preview-item-name">{asset.name}</span>
          <span className="sys-preview-item-sub">{asset.version} · {asset.assignmentScope}</span>
        </span>
      </span>
      <span className="sys-info-tag sys-info-tag--primary">{formatAssetType(asset.assetType)}</span>
    </button>
  );
}

function MyAssetPreviewItem({ asset, onClick }: { asset: MyAssetRow; onClick: () => void }) {
  const isDraft = asset.status === "draft";
  return (
    <button type="button" className="sys-preview-item asset-preview-item" onClick={onClick}>
        <span className="sys-preview-item-left">
          <span className="sys-preview-item-icon sys-card-avatar--cap">
          <AssetTypeIcon type={asset.assetType} size={16} />
        </span>
        <span className="min-w-0">
          <span className="sys-preview-item-name">{asset.name}</span>
          <span className="sys-preview-item-sub">{formatAssetType(asset.assetType)} · {asset.version}</span>
        </span>
      </span>
      <span className={`sys-info-tag ${isDraft ? "sys-info-tag--warn" : "sys-info-tag--success"}`}>{formatStatus(asset.status)}</span>
    </button>
  );
}

function AssetTypeIcon({ type, size = 22 }: { type: string; size?: number }) {
  const Icon = type === "agent_template"
    ? Bot
    : type === "mcp"
      ? Boxes
      : type === "prompt_template"
        ? FileText
        : type === "delivery"
          ? CheckCircle2
          : BrainCircuit;
  return <Icon size={size} />;
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

function AgentTemplateConfigFields({
  config,
  promptTemplateOptions,
  skillOptions,
  mcpOptions,
  readOnly = false,
  onChange,
  onPromptTemplateChange,
}: {
  config: Record<string, unknown> | undefined;
  promptTemplateOptions: Array<{ value: string; label: string }>;
  skillOptions: Array<{ value: string; label: string }>;
  mcpOptions: Array<{ value: string; label: string }>;
  readOnly?: boolean;
  onChange: (config: Record<string, unknown>) => void;
  onPromptTemplateChange: (templateId: string) => void;
}) {
  const systemPromptTemplateId = getConfigString(config, "systemPromptTemplateId") || "none";

  return (
    <>
      <div className="sys-field">
        <label className="sys-field-label">系统提示词模板</label>
        <Select
          className="agent-admin-select w-full"
          classNames={adminSelectClassNames}
          prefix={<FileText className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
          suffixIcon={adminSelectSuffixIcon}
          disabled={readOnly}
          value={systemPromptTemplateId}
          options={promptTemplateOptions}
          onChange={onPromptTemplateChange}
        />
        <div className="sys-field-hint">只能引用已发布的提示词模板或「对我开放」的系统提示词；也可保持自定义后自行编写。</div>
      </div>
      <div className="sys-field">
        <label className="sys-field-label sys-field-label--required">系统提示词</label>
        <textarea
          className="sys-field-textarea min-h-[180px]"
          disabled={readOnly}
          value={getConfigString(config, "systemPrompt")}
          placeholder="可直接编写系统提示词，或先选择上方模板后在此继续调整"
          onChange={(event) => onChange({ ...(config ?? {}), systemPrompt: event.target.value })}
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
            disabled={readOnly}
            value={getConfigIds(config, "skillIds")}
            options={skillOptions}
            onChange={(skillIds) => onChange({ ...(config ?? {}), skillIds })}
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
            disabled={readOnly}
            value={getConfigIds(config, "mcpIds")}
            options={mcpOptions}
            onChange={(mcpIds) => onChange({ ...(config ?? {}), mcpIds })}
          />
        </div>
      </div>
    </>
  );
}

function normalizeEditDraft(draft: UpdateMyAssetRequest, assetType: AssetType): UpdateMyAssetRequest {
  const base = {
    ...draft,
    name: draft.name.trim(),
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
        systemPromptTemplateId: getConfigString(draft.config, "systemPromptTemplateId") || "none",
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
