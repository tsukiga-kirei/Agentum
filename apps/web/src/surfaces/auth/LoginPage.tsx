import { useCallback, useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button, Checkbox, ConfigProvider, Form, Input, Select, Segmented, message, theme as antdTheme } from "antd";
import { Building2, KeyRound, LayoutDashboard, Settings, Shield, User } from "lucide-react";
import { useAuthStore } from "../../stores/authStore";
import { readLoginPrefs, saveLoginPrefs } from "../../stores/authSession";
import { ThemeToggle } from "../../components/ThemeToggle";
import { AgentumMark } from "../../components/brand/AgentumMark";
import { API_BASE_URL, authApi } from "../../services/apiClient";
import { firstAllowedSurfacePath, paths } from "../../routes/paths";
import type { LoginResponse, PortalType } from "../../types/auth";

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
  const ssoProviders = useAuthStore((s) => s.ssoProviders);
  const fetchSsoProviders = useAuthStore((s) => s.fetchSsoProviders);
  const login = useAuthStore((s) => s.login);
  const completeSsoLogin = useAuthStore((s) => s.completeSsoLogin);
  const user = useAuthStore((s) => s.user);
  const menus = useAuthStore((s) => s.menus);
  const themeMode = useAuthStore((s) => s.themeMode);
  const bootstrapRequired = useAuthStore((s) => s.bootstrapRequired);
  const navigate = useNavigate();
  const location = useLocation();
  const isDark = themeMode === "dark";

  const [form] = Form.useForm<LoginFormValues>();
  const selectedTenantId = Form.useWatch("tenantId", form);
  const [activePortal, setActivePortal] = useState<PortalType>("business");
  const [loading, setLoading] = useState(false);
  const [ssoLoadingProviderId, setSsoLoadingProviderId] = useState<string | null>(null);
  const [messageApi, messageContextHolder] = message.useMessage();

  useEffect(() => {
    if (bootstrapRequired) {
      navigate(paths.setup, { replace: true });
      return;
    }

    if (!user) {
      return;
    }
    const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
    navigate(from ?? firstAllowedSurfacePath(menus), { replace: true });
  }, [bootstrapRequired, location.state, menus, navigate, user]);

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
  const oidcSsoProviders = ssoProviders.filter((provider) => provider.providerType === "oidc");

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
    const notice = window.sessionStorage.getItem("agentum_auth_notice");
    if (notice) {
      window.sessionStorage.removeItem("agentum_auth_notice");
      showLoginError(notice);
    }
  }, [showLoginError]);

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

  // 恢复上次登录页偏好；只有勾选“记住账号”才恢复用户名，密码始终交给浏览器密码管理器。
  useEffect(() => {
    const prefs = readLoginPrefs();

    if (!prefs) {
      return;
    }

    setActivePortal(prefs.portal);
    form.setFieldsValue({
      rememberMe: prefs.rememberMe,
      username: prefs.rememberMe ? prefs.username ?? "" : "",
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

  useEffect(() => {
    if (!shouldSelectTenant) {
      void fetchSsoProviders(undefined);
      return;
    }

    void fetchSsoProviders(typeof selectedTenantId === "string" ? selectedTenantId : undefined);
  }, [fetchSsoProviders, selectedTenantId, shouldSelectTenant]);

  useEffect(() => {
    const expectedOrigin = new URL(API_BASE_URL || window.location.origin, window.location.origin).origin;

    function handleSsoMessage(event: MessageEvent) {
      if (event.origin !== expectedOrigin) {
        return;
      }

      const data = event.data as { type?: string; payload?: LoginResponse };
      if (data.type !== "agentum:sso-login" || !data.payload?.token) {
        return;
      }

      const rememberMe = Boolean(form.getFieldValue("rememberMe"));
      completeSsoLogin(data.payload, rememberMe);
      saveLoginPrefs({
        rememberMe,
        portal: activePortal,
        tenantId: shouldSelectTenant ? selectedTenantId : undefined,
        username: data.payload.user.username,
      });
      setSsoLoadingProviderId(null);
    }

    window.addEventListener("message", handleSsoMessage);
    return () => window.removeEventListener("message", handleSsoMessage);
  }, [activePortal, completeSsoLogin, form, selectedTenantId, shouldSelectTenant]);

  useEffect(() => {
    const raw = window.localStorage.getItem("agentum_sso_callback");
    if (!raw) {
      return;
    }
    window.localStorage.removeItem("agentum_sso_callback");
    try {
      const response = JSON.parse(raw) as LoginResponse;
      if (response.token) {
        completeSsoLogin(response, Boolean(form.getFieldValue("rememberMe")));
      }
    } catch (error) {
      console.warn("[auth] 企业 SSO 回调缓存解析失败", { message: error instanceof Error ? error.message : "unknown" });
    }
  }, [completeSsoLogin, form]);

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
        username: rememberMe ? username : undefined,
      });
    } finally {
      setLoading(false);
    }
  }

  async function handleSsoLogin(provider: { id: string; providerType: string }) {
    clearLoginError();

    if (!selectedTenantId || typeof selectedTenantId !== "string") {
      showLoginError("请选择租户后再使用企业 SSO 登录");
      return;
    }

    setSsoLoadingProviderId(provider.id);
    if (provider.providerType === "basic") {
      showLoginError("当前租户启用了 Basic 单点入口，请从已授权业务系统进入 Agentum");
      setSsoLoadingProviderId(null);
      return;
    }

    const url = authApi.ssoAuthorizeUrl(selectedTenantId, provider.id, activePortal);
    const popup = window.open(url, "agentum-sso-login", "width=720,height=760");

    if (!popup) {
      window.location.href = url;
      return;
    }

    popup.focus();
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

                {/* 只记住账号；密码交给浏览器/系统密码管理器，登录态完全由 Token 控制。 */}
                <div className="login-form-actions">
                  <Form.Item name="rememberMe" valuePropName="checked" noStyle>
                    <Checkbox>记住账号</Checkbox>
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

            {shouldSelectTenant && oidcSsoProviders.length > 0 ? (
              <div className="login-sso-placeholder">
                <div className="login-sso-actions">
                  {oidcSsoProviders.map((provider) => (
                    <Button
                      key={provider.id}
                      block
                      className="login-sso-button"
                      loading={ssoLoadingProviderId === provider.id}
                      type="default"
                      onClick={() => void handleSsoLogin(provider)}
                    >
                      {provider.name}
                    </Button>
                  ))}
                </div>
              </div>
            ) : null}

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
