import { useCallback, useEffect, useState } from "react";
import { Button, Checkbox, ConfigProvider, Form, Input, Select, Segmented, message, theme as antdTheme } from "antd";
import { Building2, KeyRound, LayoutDashboard, Settings, Shield, User } from "lucide-react";
import { useAuthStore } from "../../stores/authStore";
import { readLoginPrefs, saveLoginPrefs } from "../../stores/authSession";
import { ThemeToggle } from "../../components/ThemeToggle";
import { AgentumMark } from "../../components/brand/AgentumMark";
import type { PortalType } from "../../types/auth";

// 登录入口选项，与 docs/system-overview.md 中角色定义对齐。
// 三种入口面向不同角色，登录后进入不同默认页面。
const portals: Array<{
  key: PortalType;
  icon: typeof LayoutDashboard;
  label: string;
  description: string;
  color: string;
}> = [
  {
    key: "business",
    icon: LayoutDashboard,
    label: "业务用户",
    description: "发起流程、处理待办、查看运行结果",
    color: "#4f46e5",
  },
  {
    key: "tenant_admin",
    icon: Settings,
    label: "租户管理",
    description: "管理租户成员、角色权限和需求配置",
    color: "#f59e0b",
  },
  {
    key: "system_admin",
    icon: Shield,
    label: "系统管理",
    description: "全局配置、模型管理和审计",
    color: "#dc2626",
  },
];

type LoginFormValues = {
  tenantId?: string;
  username?: string;
  password?: string;
  rememberMe?: boolean;
};

export function LoginPage() {
  const tenants = useAuthStore((s) => s.tenants);
  const tenantsLoading = useAuthStore((s) => s.tenantsLoading);
  const fetchTenants = useAuthStore((s) => s.fetchTenants);
  const login = useAuthStore((s) => s.login);
  const themeMode = useAuthStore((s) => s.themeMode);
  const isDark = themeMode === "dark";

  const [form] = Form.useForm<LoginFormValues>();
  const [activePortal, setActivePortal] = useState<PortalType>("business");
  const [loading, setLoading] = useState(false);
  const [messageApi, messageContextHolder] = message.useMessage();

  const currentPortal = portals.find((p) => p.key === activePortal) ?? portals[0];
  const shouldSelectTenant = activePortal !== "system_admin";
  const portalOptions = portals.map((portal) => {
    const Icon = portal.icon;

    return {
      value: portal.key,
      label: (
        <span className="login-portal-option">
          <Icon className="login-portal-option-icon" aria-hidden="true" />
          <span>{portal.label}</span>
        </span>
      ),
    };
  });
  const tenantOptions = tenants.map((tenant) => ({
    value: tenant.id,
    label: (
      <span className="agent-tenant-option">
        <span className="agent-tenant-name">{tenant.name}</span>
        <span className="agent-tenant-code">{tenant.code}</span>
      </span>
    ),
  }));

  const showLoginError = useCallback((content: string) => {
    void messageApi.open({
      key: "login-error",
      type: "error",
      content,
      duration: 3,
    });
  }, [messageApi]);

  const clearLoginError = useCallback(() => {
    messageApi.destroy("login-error");
  }, [messageApi]);

  useEffect(() => {
    let active = true;

    fetchTenants()
      .catch((tenantError) => {
        if (active) {
          showLoginError(tenantError instanceof Error ? tenantError.message : "无法加载租户列表");
        }
      });

    return () => {
      active = false;
    };
  }, [fetchTenants, showLoginError]);

  // 恢复上次登录页偏好（入口、租户、用户名、记住我）；不恢复密码。
  useEffect(() => {
    const prefs = readLoginPrefs();

    if (!prefs) {
      return;
    }

    setActivePortal(prefs.portal);
    form.setFieldsValue({
      rememberMe: prefs.rememberMe,
      username: prefs.username ?? "",
      tenantId: prefs.tenantId,
    });
  }, [form]);

  useEffect(() => {
    const prefs = readLoginPrefs();
    const preferredTenantId = prefs?.tenantId;
    const matchedTenant = preferredTenantId ? tenants.find((tenant) => tenant.id === preferredTenantId) : undefined;

    if (matchedTenant) {
      form.setFieldValue("tenantId", matchedTenant.id);
      return;
    }

    if (!form.getFieldValue("tenantId") && tenants[0]) {
      form.setFieldValue("tenantId", tenants[0].id);
    }
  }, [form, tenants]);

  function handlePortalChange(portal: PortalType) {
    setActivePortal(portal);
    clearLoginError();
  }

  async function handleSubmit(values: LoginFormValues) {
    clearLoginError();

    const username = values.username?.trim() ?? "";
    const password = values.password?.trim() ?? "";
    const tenantId = shouldSelectTenant ? values.tenantId : undefined;

    if (shouldSelectTenant && !tenantId) {
      showLoginError("请选择租户");
      return;
    }

    if (!username.trim()) {
      showLoginError("请输入用户名");
      return;
    }

    if (!password.trim()) {
      showLoginError("请输入密码");
      return;
    }

    setLoading(true);

    const rememberMe = values.rememberMe ?? false;

    try {
      const result = await login(username, password, activePortal, shouldSelectTenant ? tenantId : undefined, rememberMe);

      if (!result.success) {
        showLoginError(result.message ?? (shouldSelectTenant ? "租户、用户名或密码不正确" : "用户名或密码不正确"));
        return;
      }

      saveLoginPrefs({
        rememberMe,
        portal: activePortal,
        tenantId: shouldSelectTenant ? tenantId : undefined,
        username,
      });
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
    {messageContextHolder}
    <div className={`login-page ${isDark ? "dark" : ""}`}>
      {/* 动态背景 */}
      <div className="login-bg">
        <div className="login-bg-shape login-bg-shape--1" />
        <div className="login-bg-shape login-bg-shape--2" />
        <div className="login-bg-shape login-bg-shape--3" />
      </div>

      {/* 右上角主题切换（与 AuraOA 一致） */}
      <div className="login-theme-toggle">
        <ThemeToggle />
      </div>

      {/* 登录容器 */}
      <div className="login-container">
        {/* 左侧品牌区 */}
        <div className="login-branding">
          <div className="login-brand-content">
            <div className="login-brand-mark">
              <AgentumMark className="h-full w-full" variant="full" />
            </div>
            <h1 className="login-brand-title">Agentum</h1>
            <p className="login-brand-subtitle">智能体装配式工作流平台</p>
            <div className="login-feature-list">
              {["原子能力独立管理与版本化", "工作流可审计执行与暂停恢复", "多智能体协作与交付闭环"].map((feature) => (
                <div key={feature} className="login-feature-item">
                  <span className="login-feature-dot" />
                  <span>{feature}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* 右侧登录表单 */}
        <div className="login-form-side">
          <div className="login-panel">
            <h2 className="login-heading">欢迎回来</h2>
            <p className="login-subheading">选择身份入口并登录工作台</p>

            {/* 入口选择器 - 药丸式 */}
            <Segmented
              block
              className={`login-portal-segmented login-portal-segmented--${activePortal}`}
              options={portalOptions}
              value={activePortal}
              onChange={(value) => handlePortalChange(value as PortalType)}
            />

            {/* 当前入口描述 */}
            <div className={`login-portal-description login-portal-description--${activePortal}`}>
              <span className="login-portal-description-dot" />
              {currentPortal.description}
            </div>

            {/* 登录表单 */}
            <ConfigProvider
              theme={{
                algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
                token: {
                  borderRadius: 8,
                  colorBgContainer: "var(--color-bg-input)",
                  colorBgElevated: "var(--color-bg-card)",
                  colorBorder: "var(--color-border)",
                  colorPrimary: currentPortal.color,
                  colorText: "var(--color-text-primary)",
                  colorTextPlaceholder: "var(--color-text-tertiary)",
                  controlHeight: 48,
                  fontFamily: "var(--font-sans)",
                },
                components: {
                  Select: {
                    optionActiveBg: isDark ? "#1a2540" : "#f5f6fa",
                    optionSelectedBg: isDark ? "rgba(79, 70, 229, 0.2)" : "#eef2ff",
                  },
                },
              }}
            >
              <Form
                form={form}
                className="login-form"
                initialValues={{ rememberMe: false }}
                onFinish={handleSubmit}
                onValuesChange={clearLoginError}
              >
                {shouldSelectTenant ? (
                  <Form.Item name="tenantId">
                    <Select
                      aria-label="选择租户"
                      className="agent-tenant-select"
                      classNames={{ popup: { root: "agent-select-dropdown" } }}
                      options={tenantOptions}
                      placeholder="请选择租户"
                      prefix={<Building2 className="h-5 w-5 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                      loading={tenantsLoading}
                      disabled={tenantsLoading}
                    />
                  </Form.Item>
                ) : null}

                <Form.Item name="username">
                  <Input
                    autoComplete="username"
                    className="login-ant-input"
                    placeholder="用户名"
                    prefix={<User className="login-input-icon" aria-hidden="true" />}
                  />
                </Form.Item>

                <Form.Item name="password">
                  <Input.Password
                    autoComplete="current-password"
                    className="login-ant-input"
                    placeholder="密码"
                    prefix={<KeyRound className="login-input-icon" aria-hidden="true" />}
                  />
                </Form.Item>

                {/* 记住我 */}
                <div className="login-form-actions">
                  <Form.Item name="rememberMe" valuePropName="checked" noStyle>
                    <Checkbox>记住我</Checkbox>
                  </Form.Item>
                  <Button type="link" className="login-forgot-button">
                    忘记密码？
                  </Button>
                </div>

                {/* 登录按钮 */}
                <Button
                  block
                  className="login-submit-button"
                  htmlType="submit"
                  loading={loading}
                  size="large"
                  type="primary"
                >
                  {`以${currentPortal.label}身份登录`}
                </Button>
              </Form>
            </ConfigProvider>

            {/* SSO 占位 */}
            <div className="login-sso-placeholder">
              <p>企业 SSO 登录即将支持</p>
            </div>

            {/* 页脚 */}
            <div className="login-footer">
              Agentum © 2026
            </div>
          </div>
        </div>
      </div>
    </div>
    </>
  );
}
