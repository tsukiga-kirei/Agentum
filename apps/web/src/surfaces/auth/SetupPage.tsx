import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, ConfigProvider, Form, Input, message, theme as antdTheme } from "antd";
import { BadgeCheck, KeyRound, Mail, ShieldCheck, User } from "lucide-react";
import { AgentumMark } from "../../components/brand/AgentumMark";
import { ThemeToggle } from "../../components/ThemeToggle";
import { paths } from "../../routes/paths";
import { useAuthStore } from "../../stores/authStore";
import { isDarkTheme } from "../../utils/theme";

type SetupFormValues = {
  username?: string;
  displayName?: string;
  email?: string;
  password?: string;
  confirmPassword?: string;
};

export function SetupPage() {
  const bootstrapRequired = useAuthStore((state) => state.bootstrapRequired);
  const createBootstrapAdmin = useAuthStore((state) => state.createBootstrapAdmin);
  const themeMode = useAuthStore((state) => state.themeMode);
  const navigate = useNavigate();
  const [form] = Form.useForm<SetupFormValues>();
  const [loading, setLoading] = useState(false);
  const [messageApi, messageContextHolder] = message.useMessage();
  const isDark = isDarkTheme(themeMode);

  useEffect(() => {
    if (!bootstrapRequired) {
      navigate(paths.login, { replace: true });
    }
  }, [bootstrapRequired, navigate]);

  async function handleSubmit(values: SetupFormValues) {
    const username = values.username?.trim() ?? "";
    const displayName = values.displayName?.trim() ?? "";
    const email = values.email?.trim() ?? "";
    const password = values.password ?? "";
    const confirmPassword = values.confirmPassword ?? "";

    if (password !== confirmPassword) {
      form.setFields([{ name: "confirmPassword", errors: ["两次输入的密码不一致"] }]);
      return;
    }

    setLoading(true);
    try {
      const result = await createBootstrapAdmin({
        username,
        displayName,
        password,
        email: email || undefined,
      });

      if (!result.success) {
        void messageApi.error(result.message ?? "系统管理员初始化失败");
        return;
      }

      void messageApi.success("系统管理员已创建，请使用系统管理入口登录");
      navigate(paths.login, { replace: true });
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      {messageContextHolder}
      <div className={`login-page setup-page ${isDark ? "dark" : ""}`}>
        <div className="login-bg">
          <div className="login-bg-shape login-bg-shape--1" />
          <div className="login-bg-shape login-bg-shape--2" />
          <div className="login-bg-shape login-bg-shape--3" />
        </div>

        <div className="login-theme-toggle">
          <ThemeToggle />
        </div>

        <div className="login-container setup-container">
          <div className="login-branding setup-branding">
            <div className="login-brand-content">
              <div className="login-brand-mark">
                <AgentumMark className="h-full w-full" variant="full" />
              </div>
              <h1 className="login-brand-title">Agentum</h1>
              <p className="login-brand-subtitle">初始化平台治理入口</p>
              <div className="setup-status-panel">
                <ShieldCheck className="setup-status-icon" aria-hidden="true" />
                <div>
                  <strong>创建首个系统管理员</strong>
                  <span>该账号用于进入系统管理，后续再创建租户与租户管理员。</span>
                </div>
              </div>
            </div>
          </div>

          <div className="login-form-side">
            <div className="login-panel">
              <div className="setup-heading-mark">
                <BadgeCheck aria-hidden="true" />
              </div>
              <h2 className="login-heading">初始化系统</h2>
              <p className="login-subheading">当前没有任何用户，请创建首个系统管理员账号</p>

              <ConfigProvider
                theme={{
                  algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
                  token: {
                    borderRadius: 8,
                    colorBgContainer: "var(--color-bg-input)",
                    colorBgElevated: "var(--color-bg-card)",
                    colorBorder: "var(--color-border)",
                    colorPrimary: "#dc2626",
                    colorText: "var(--color-text-primary)",
                    colorTextPlaceholder: "var(--color-text-tertiary)",
                    controlHeight: 48,
                    fontFamily: "var(--font-sans)",
                  },
                }}
              >
                <Form form={form} className="login-form setup-form" onFinish={handleSubmit}>
                  <Form.Item
                    name="username"
                    rules={[
                      { required: true, message: "请输入用户名" },
                      { pattern: /^[a-zA-Z0-9_]{3,100}$/, message: "用户名需为 3 到 100 位字母、数字或下划线" },
                    ]}
                  >
                    <Input
                      autoComplete="username"
                      className="login-ant-input"
                      placeholder="用户名"
                      prefix={<User className="login-input-icon" aria-hidden="true" />}
                    />
                  </Form.Item>

                  <Form.Item name="displayName" rules={[{ required: true, message: "请输入显示名称" }]}>
                    <Input
                      autoComplete="name"
                      className="login-ant-input"
                      placeholder="显示名称"
                      prefix={<ShieldCheck className="login-input-icon" aria-hidden="true" />}
                    />
                  </Form.Item>

                  <Form.Item name="email" rules={[{ type: "email", message: "请输入有效邮箱" }]}>
                    <Input
                      autoComplete="email"
                      className="login-ant-input"
                      placeholder="邮箱，可选"
                      prefix={<Mail className="login-input-icon" aria-hidden="true" />}
                    />
                  </Form.Item>

                  <Form.Item name="password" rules={[{ required: true, min: 8, message: "密码至少 8 位" }]}>
                    <Input.Password
                      autoComplete="new-password"
                      className="login-ant-input"
                      placeholder="初始密码"
                      prefix={<KeyRound className="login-input-icon" aria-hidden="true" />}
                    />
                  </Form.Item>

                  <Form.Item name="confirmPassword" rules={[{ required: true, message: "请再次输入密码" }]}>
                    <Input.Password
                      autoComplete="new-password"
                      className="login-ant-input"
                      placeholder="确认密码"
                      prefix={<KeyRound className="login-input-icon" aria-hidden="true" />}
                    />
                  </Form.Item>

                  <Button
                    block
                    className="login-submit-button setup-submit-button"
                    htmlType="submit"
                    loading={loading}
                    size="large"
                    type="primary"
                  >
                    创建系统管理员
                  </Button>
                </Form>
              </ConfigProvider>

              <div className="login-footer">Agentum © 2026</div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
