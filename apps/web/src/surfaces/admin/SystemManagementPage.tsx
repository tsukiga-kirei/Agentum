import { useCallback, useEffect, useState } from "react";
import {
  Activity,
  Boxes,
  Building2,
  DatabaseZap,
  KeyRound,
  LayoutDashboard,
  Mail,
  ServerCog,
  Settings2,
} from "lucide-react";
import { Button, Empty, Form, Input, Modal, Segmented, Select, Spin, Table, Tag, message } from "antd";
import type { ColumnsType } from "antd/es/table";
import { AgentumApiError, systemApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type {
  CreateModelProviderRequest,
  CreateSystemCapabilityRequest,
  CreateTenantCapabilityGrantRequest,
  ModelProviderRow,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantRow,
  TenantCapabilityGrantRow,
} from "../../types/system";

type SystemSection = "overview" | "tenants" | "models" | "capabilities" | "grants" | "audit";

const capabilityTypeOptions = [
  { value: "mcp", label: "MCP" },
  { value: "skill", label: "Skill" },
  { value: "model", label: "模型" },
  { value: "prompt_template", label: "提示词模板" },
  { value: "delivery", label: "交付" },
];

function formatModelStatus(status: string): string {
  if (status === "active") {
    return "可用";
  }
  if (status === "draft") {
    return "草稿";
  }
  return status;
}

function formatRisk(level: string): string {
  if (level === "low") {
    return "低";
  }
  if (level === "medium") {
    return "中";
  }
  if (level === "high") {
    return "高";
  }
  return level;
}

function formatCapabilityType(t: string): string {
  const found = capabilityTypeOptions.find((o) => o.value === t);
  return found ? found.label : t;
}

function RiskTag({ level }: { level: string }) {
  const label = `${formatRisk(level)}风险`;
  if (level === "high") {
    return <Tag color="red">{label}</Tag>;
  }
  if (level === "medium") {
    return <Tag color="orange">{label}</Tag>;
  }
  return <Tag color="green">{label}</Tag>;
}

// 系统管理：模块切换与登录页一致（Ant Segmented + login-portal-segmented），数据来自 /api/system/*。
export function SystemManagementPage() {
  const token = useAuthStore((s) => s.token);
  const themeMode = useAuthStore((s) => s.themeMode);
  const [messageApi, messageContextHolder] = message.useMessage();
  const darkModalClassName = themeMode === "dark" ? "agent-dark-modal" : undefined;

  const [section, setSection] = useState<SystemSection>("overview");
  const [loading, setLoading] = useState(false);

  const [summary, setSummary] = useState<SystemSummary | null>(null);
  const [tenants, setTenants] = useState<SystemTenantRow[]>([]);
  const [modelProviders, setModelProviders] = useState<ModelProviderRow[]>([]);
  const [capabilities, setCapabilities] = useState<SystemCapabilityRow[]>([]);
  const [grants, setGrants] = useState<TenantCapabilityGrantRow[]>([]);

  const [modelModalOpen, setModelModalOpen] = useState(false);
  const [capModalOpen, setCapModalOpen] = useState(false);
  const [grantModalOpen, setGrantModalOpen] = useState(false);

  const [modelForm] = Form.useForm<CreateModelProviderRequest>();
  const [capForm] = Form.useForm<CreateSystemCapabilityRequest>();
  const [grantForm] = Form.useForm<CreateTenantCapabilityGrantRequest>();

  const handleApiError = useCallback(
    (err: unknown, fallback: string) => {
      if (err instanceof AgentumApiError) {
        messageApi.error(err.message);
        return;
      }
      messageApi.error(fallback);
    },
    [messageApi],
  );

  const loadSummary = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    try {
      const data = await systemApi.summary(token);
      setSummary(data);
    } catch (e) {
      handleApiError(e, "加载系统概览失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError]);

  const loadTenants = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    try {
      setTenants(await systemApi.listTenants(token));
    } catch (e) {
      handleApiError(e, "加载租户列表失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError]);

  const loadModels = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    try {
      setModelProviders(await systemApi.listModelProviders(token));
    } catch (e) {
      handleApiError(e, "加载模型供应商失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError]);

  const loadCapabilities = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    try {
      setCapabilities(await systemApi.listCapabilities(token));
    } catch (e) {
      handleApiError(e, "加载系统能力失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError]);

  const loadGrants = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    try {
      setGrants(await systemApi.listGrants(token));
    } catch (e) {
      handleApiError(e, "加载租户能力授权失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError]);

  useEffect(() => {
    if (!token) {
      return;
    }
    if (section === "overview") {
      void loadSummary();
    } else if (section === "tenants") {
      void loadTenants();
    } else if (section === "models") {
      void loadModels();
    } else if (section === "capabilities") {
      void loadCapabilities();
    } else if (section === "grants") {
      void loadGrants();
      void loadTenants();
      void loadCapabilities();
    }
  }, [section, token, loadSummary, loadTenants, loadModels, loadCapabilities, loadGrants]);

  const patchTenantStatus = async (tenantId: string, status: string) => {
    if (!token) {
      return;
    }
    try {
      await systemApi.updateTenantStatus(tenantId, token, { status });
      messageApi.success("租户状态已更新");
      void loadTenants();
      void loadSummary();
    } catch (e) {
      handleApiError(e, "更新租户状态失败");
    }
  };

  const submitModel = async () => {
    if (!token) {
      return;
    }
    try {
      const values = await modelForm.validateFields();
      await systemApi.createModelProvider(token, values);
      messageApi.success("已注册模型供应商");
      setModelModalOpen(false);
      modelForm.resetFields();
      void loadModels();
      void loadSummary();
    } catch (e) {
      if (e && typeof e === "object" && "errorFields" in e) {
        return;
      }
      handleApiError(e, "注册模型供应商失败");
    }
  };

  const submitCapability = async () => {
    if (!token) {
      return;
    }
    try {
      const values = await capForm.validateFields();
      await systemApi.createCapability(token, values);
      messageApi.success("已注册系统能力");
      setCapModalOpen(false);
      capForm.resetFields();
      void loadCapabilities();
      void loadSummary();
    } catch (e) {
      if (e && typeof e === "object" && "errorFields" in e) {
        return;
      }
      handleApiError(e, "注册系统能力失败");
    }
  };

  const submitGrant = async () => {
    if (!token) {
      return;
    }
    try {
      const values = await grantForm.validateFields();
      await systemApi.createGrant(token, {
        tenantId: values.tenantId,
        capabilityId: values.capabilityId,
        status: values.status,
      });
      messageApi.success("已新增租户能力授权");
      setGrantModalOpen(false);
      grantForm.resetFields();
      void loadGrants();
      void loadSummary();
    } catch (e) {
      if (e && typeof e === "object" && "errorFields" in e) {
        return;
      }
      handleApiError(e, "新增授权失败");
    }
  };

  const tenantColumns: ColumnsType<SystemTenantRow> = [
    { title: "名称", dataIndex: "name", key: "name", ellipsis: true },
    { title: "编码", dataIndex: "code", key: "code", width: 140 },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 168,
      render: (status: string, record) => (
        <Select
          className="min-w-[132px]"
          size="middle"
          value={status}
          options={[
            { value: "active", label: "启用" },
            { value: "suspended", label: "暂停" },
          ]}
          onChange={(v) => void patchTenantStatus(record.id, v)}
        />
      ),
    },
  ];

  const modelColumns: ColumnsType<ModelProviderRow> = [
    { title: "名称", dataIndex: "name", key: "name", ellipsis: true },
    { title: "类型", dataIndex: "providerType", key: "providerType", width: 168 },
    {
      title: "基址",
      dataIndex: "baseUrl",
      key: "baseUrl",
      ellipsis: true,
      render: (v: string | null) => <span className="text-[var(--color-text-secondary)]">{v ?? "—"}</span>,
    },
    {
      title: "默认模型",
      dataIndex: "defaultModel",
      key: "defaultModel",
      width: 140,
      render: (v: string | null) => v ?? "—",
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 96,
      render: (s: string) => <Tag color={s === "active" ? "green" : "default"}>{formatModelStatus(s)}</Tag>,
    },
  ];

  const capColumns: ColumnsType<SystemCapabilityRow> = [
    {
      title: "类型",
      dataIndex: "capabilityType",
      key: "capabilityType",
      width: 120,
      render: (t: string) => <Tag>{formatCapabilityType(t)}</Tag>,
    },
    { title: "名称", dataIndex: "name", key: "name", ellipsis: true },
    { title: "编码", dataIndex: "code", key: "code", width: 160 },
    { title: "版本", dataIndex: "version", key: "version", width: 80 },
    {
      title: "风险",
      dataIndex: "riskLevel",
      key: "riskLevel",
      width: 112,
      render: (level: string) => <RiskTag level={level} />,
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 96,
      render: (s: string) => <Tag>{s}</Tag>,
    },
  ];

  const grantColumns: ColumnsType<TenantCapabilityGrantRow> = [
    { title: "租户", key: "tenant", ellipsis: true, render: (_, r) => `${r.tenantName}（${r.tenantCode}）` },
    { title: "能力", key: "cap", ellipsis: true, render: (_, r) => `${r.capabilityName} · ${r.capabilityCode}` },
    {
      title: "类型",
      dataIndex: "capabilityType",
      key: "capabilityType",
      width: 120,
      render: (t: string) => formatCapabilityType(t),
    },
    { title: "授权状态", dataIndex: "grantStatus", key: "grantStatus", width: 112 },
  ];

  const navItems: { key: SystemSection; label: string; icon: typeof LayoutDashboard; description: string }[] = [
    { key: "overview", label: "平台概览", icon: LayoutDashboard, description: "全局统计与治理提示" },
    { key: "tenants", label: "租户", icon: Building2, description: "租户可用状态与隔离边界" },
    { key: "models", label: "模型供应商", icon: DatabaseZap, description: "模型接入与路由配置" },
    { key: "capabilities", label: "全局能力", icon: Boxes, description: "MCP / Skill / 模板等登记" },
    { key: "grants", label: "租户授权", icon: ServerCog, description: "平台向租户开放的能力包" },
    { key: "audit", label: "系统审计", icon: Activity, description: "平台级操作留痕（占位）" },
  ];

  const activeNav = navItems.find((n) => n.key === section) ?? navItems[0];

  const moduleSegmentedOptions = navItems.map((item) => {
    const Icon = item.icon;
    return {
      value: item.key,
      label: (
        <span className="login-portal-option">
          <Icon className="login-portal-option-icon" aria-hidden />
          <span>{item.label}</span>
        </span>
      ),
    };
  });

  const tableProps = {
    size: "middle" as const,
    pagination: false as const,
    className: "agent-system-admin-table",
  };

  return (
    <>
      {messageContextHolder}
      <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
        <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
          {/* 页头 */}
          <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
            <div className="flex min-w-0 gap-4">
              <div className="system-mgmt-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
                <ServerCog className="h-6 w-6" aria-hidden />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">系统管理</h1>
                  <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                    平台治理
                  </span>
                </div>
                <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                  注册底层模型与全局能力，向租户授权后再由租户管理员分配；凭证与密钥始终留在服务端。
                </p>
              </div>
            </div>
          </header>

          {/* 与登录页相同的药丸分段控件；系统管理沿用登录「系统管理」入口的红色主题 */}
          <div className="system-mgmt-module-switch mb-5">
            <div className="system-mgmt-segmented-scroll">
              <Segmented<SystemSection>
                aria-label="系统管理模块"
                value={section}
                onChange={(key) => setSection(key as SystemSection)}
                options={moduleSegmentedOptions}
                className="login-portal-segmented login-portal-segmented--system_admin system-mgmt-segmented"
              />
            </div>
            <div className="login-portal-description login-portal-description--system_admin">
              <span className="login-portal-description-dot" />
              {activeNav.description}
            </div>
          </div>

          {/* 内容卡片：有操作时顶部仅保留工具栏 */}
          <div className="overflow-hidden rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]">
            {section === "models" || section === "capabilities" || section === "grants" ? (
              <div className="flex flex-col gap-3 border-b border-[var(--color-border-light)] bg-[var(--color-bg-hover)]/50 px-5 py-3 sm:flex-row sm:items-center sm:justify-end">
                {section === "models" ? (
                  <Button type="primary" onClick={() => setModelModalOpen(true)}>
                    注册供应商
                  </Button>
                ) : null}
                {section === "capabilities" ? (
                  <Button type="primary" onClick={() => setCapModalOpen(true)}>
                    注册能力
                  </Button>
                ) : null}
                {section === "grants" ? (
                  <Button
                    type="primary"
                    onClick={() => {
                      grantForm.resetFields();
                      setGrantModalOpen(true);
                    }}
                  >
                    新增授权
                  </Button>
                ) : null}
              </div>
            ) : null}

            <div className="p-5">
              <Spin spinning={loading}>
                {section === "overview" && (
                  <div className="space-y-6">
                    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
                      <Stat label="租户总数" value={summary?.tenantTotal} hint="含已暂停" />
                      <Stat label="活跃租户" value={summary?.tenantActive} hint="登录页可见" accent />
                      <Stat label="模型供应商" value={summary?.modelProviderTotal} />
                      <Stat label="全局能力" value={summary?.systemCapabilityTotal} />
                      <Stat label="租户授权" value={summary?.tenantCapabilityGrantTotal} />
                    </div>
                    <p className="text-sm leading-relaxed text-[var(--color-text-secondary)]">
                      统计由后端实时聚合；公开租户列表仅包含 <code className="rounded bg-[var(--color-bg-hover)] px-1.5 py-0.5 text-xs">active</code>{" "}
                      租户。<code className="rounded bg-[var(--color-bg-hover)] px-1.5 py-0.5 text-xs">suspended</code> 租户无法从登录入口进入。
                    </p>
                    <div className="grid gap-4 lg:grid-cols-3">
                      <SystemNotice icon={KeyRound} title="凭证策略" detail="生产、测试与租户凭证分区存放；界面只展示脱敏状态。" />
                      <SystemNotice icon={Mail} title="交付通道" detail="邮件、OA、IM、Webhook、落库等均作为交付能力登记并授权。" />
                      <SystemNotice icon={Settings2} title="系统参数" detail="保留策略、额度、并发与审计级别后续进入独立配置模块。" />
                    </div>
                  </div>
                )}

                {section === "tenants" && (
                  <Table<SystemTenantRow> rowKey="id" columns={tenantColumns} dataSource={tenants} {...tableProps} />
                )}

                {section === "models" && (
                  <Table<ModelProviderRow> rowKey="id" columns={modelColumns} dataSource={modelProviders} {...tableProps} />
                )}

                {section === "capabilities" && (
                  <Table<SystemCapabilityRow> rowKey="id" columns={capColumns} dataSource={capabilities} {...tableProps} />
                )}

                {section === "grants" && (
                  <Table<TenantCapabilityGrantRow> rowKey="id" columns={grantColumns} dataSource={grants} {...tableProps} />
                )}

                {section === "audit" && (
                  <div className="py-12">
                    <Empty description="系统审计仅读视图将聚合平台级操作记录；当前占位，后续接入审计事件 API。" />
                  </div>
                )}
              </Spin>
            </div>
          </div>
        </div>
      </div>

      <Modal
        title="注册模型供应商"
        rootClassName={darkModalClassName}
        open={modelModalOpen}
        onOk={() => void submitModel()}
        onCancel={() => setModelModalOpen(false)}
        destroyOnClose
      >
        <Form form={modelForm} layout="vertical" className="mt-2" preserve={false}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: "请输入名称" }]}>
            <Input maxLength={160} />
          </Form.Item>
          <Form.Item name="providerType" label="供应商类型" rules={[{ required: true, message: "请输入类型" }]}>
            <Input placeholder="例如 openai-compatible" maxLength={80} />
          </Form.Item>
          <Form.Item name="baseUrl" label="基址 URL">
            <Input maxLength={500} />
          </Form.Item>
          <Form.Item name="defaultModel" label="默认模型">
            <Input maxLength={160} />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue="draft">
            <Select
              options={[
                { value: "draft", label: "草稿" },
                { value: "active", label: "可用" },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="注册系统能力"
        rootClassName={darkModalClassName}
        open={capModalOpen}
        onOk={() => void submitCapability()}
        onCancel={() => setCapModalOpen(false)}
        destroyOnClose
      >
        <Form form={capForm} layout="vertical" className="mt-2" preserve={false}>
          <Form.Item name="capabilityType" label="能力类型" rules={[{ required: true, message: "请选择类型" }]}>
            <Select options={capabilityTypeOptions} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: "请输入名称" }]}>
            <Input maxLength={160} />
          </Form.Item>
          <Form.Item name="code" label="编码" rules={[{ required: true, message: "请输入编码" }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="version" label="版本" initialValue="v1">
            <Input maxLength={40} />
          </Form.Item>
          <Form.Item name="riskLevel" label="风险等级" initialValue="low">
            <Select
              options={[
                { value: "low", label: "低" },
                { value: "medium", label: "中" },
                { value: "high", label: "高" },
              ]}
            />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue="draft">
            <Select
              options={[
                { value: "draft", label: "草稿" },
                { value: "active", label: "启用" },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新增租户能力授权"
        rootClassName={darkModalClassName}
        open={grantModalOpen}
        onOk={() => void submitGrant()}
        onCancel={() => setGrantModalOpen(false)}
        destroyOnClose
      >
        <Form form={grantForm} layout="vertical" className="mt-2" preserve={false}>
          <Form.Item name="tenantId" label="租户" rules={[{ required: true, message: "请选择租户" }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={tenants.map((t) => ({
                value: t.id,
                label: `${t.name} (${t.code})`,
              }))}
            />
          </Form.Item>
          <Form.Item name="capabilityId" label="系统能力" rules={[{ required: true, message: "请选择能力" }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={capabilities.map((c) => ({
                value: c.id,
                label: `${c.name} · ${c.code}`,
              }))}
            />
          </Form.Item>
          <Form.Item name="status" label="授权状态" initialValue="enabled">
            <Select
              options={[
                { value: "enabled", label: "启用" },
                { value: "disabled", label: "停用" },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

function Stat({ label, value, hint, accent }: { label: string; value: number | undefined; hint?: string; accent?: boolean }) {
  return (
    <div
      className={`rounded-[var(--radius-md)] border p-4 transition-shadow ${
        accent
          ? "border-[var(--color-primary)]/25 bg-[var(--color-primary-bg)] shadow-[var(--shadow-xs)]"
          : "border-[var(--color-border-light)] bg-[var(--color-bg-hover)]/60"
      } `}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-medium text-[var(--color-text-secondary)]">{label}</p>
        {hint ? <span className="shrink-0 text-[10px] text-[var(--color-text-tertiary)]">{hint}</span> : null}
      </div>
      <p className="mt-2 text-2xl font-semibold tabular-nums tracking-tight text-[var(--color-text-primary)]">{value ?? "—"}</p>
    </div>
  );
}

function SystemNotice({ icon: Icon, title, detail }: { icon: typeof KeyRound; title: string; detail: string }) {
  return (
    <article className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)]/40 p-4">
      <div className="flex items-center gap-2">
        <span className="flex h-8 w-8 items-center justify-center rounded-[var(--radius-sm)] bg-[var(--color-bg-card)] text-[var(--color-primary)] ring-1 ring-[var(--color-border-light)]">
          <Icon className="h-4 w-4" aria-hidden="true" />
        </span>
        <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">{title}</h3>
      </div>
      <p className="agent-muted mt-3 text-sm leading-relaxed">{detail}</p>
    </article>
  );
}
