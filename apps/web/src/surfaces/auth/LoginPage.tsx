import { FormEvent, useState } from "react";
import { KeyRound, LayoutDashboard, Settings, Shield, User } from "lucide-react";
import { useAuthStore } from "../../stores/authStore";
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
    key: "space_admin",
    icon: Settings,
    label: "空间管理",
    description: "管理空间成员、资产和权限策略",
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

export function LoginPage() {
  const login = useAuthStore((s) => s.login);
  const themeMode = useAuthStore((s) => s.themeMode);
  const isDark = themeMode === "dark";

  const [activePortal, setActivePortal] = useState<PortalType>("business");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [rememberMe, setRememberMe] = useState(false);

  const currentPortal = portals.find((p) => p.key === activePortal) ?? portals[0];

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");

    if (!username.trim()) {
      setError("请输入用户名");
      return;
    }

    if (!password.trim()) {
      setError("请输入密码");
      return;
    }

    setLoading(true);

    try {
      // 模拟网络延迟，让用户看到加载状态
      await new Promise((resolve) => setTimeout(resolve, 600));
      const success = await login(username, password, activePortal);

      if (!success) {
        setError("用户名或密码不正确");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className={`login-page ${isDark ? "dark" : ""}`}>
      {/* 动态背景 */}
      <div className="login-bg">
        <div className="login-bg-shape login-bg-shape--1" />
        <div className="login-bg-shape login-bg-shape--2" />
        <div className="login-bg-shape login-bg-shape--3" />
      </div>

      {/* 右上角主题切换（与 AuraOA 一致） */}
      <div style={{ position: "absolute", top: 20, right: 20, zIndex: 10 }}>
        <ThemeToggle />
      </div>

      {/* 登录容器 */}
      <div className="login-container">
        {/* 左侧品牌区 */}
        <div className="login-branding">
          <div style={{ position: "relative", zIndex: 1 }}>
            <div className="login-brand-mark">
              <AgentumMark className="h-full w-full" variant="full" />
            </div>
            <h1 style={{ fontSize: 28, fontWeight: 700, color: "#fff", margin: "0 0 8px", letterSpacing: "-0.02em" }}>
              Agentum
            </h1>
            <p style={{ fontSize: 15, color: "rgba(255,255,255,0.8)", margin: "0 0 36px" }}>
              智能体装配式工作流平台
            </p>
            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              {["原子能力独立管理与版本化", "工作流可审计执行与暂停恢复", "多智能体协作与交付闭环"].map((feature) => (
                <div key={feature} style={{ display: "flex", alignItems: "center", gap: 10, color: "rgba(255,255,255,0.9)", fontSize: 13 }}>
                  <span style={{ width: 7, height: 7, borderRadius: "50%", background: "#22d3ee", flexShrink: 0 }} />
                  <span>{feature}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* 右侧登录表单 */}
        <div className="login-form-side">
          <div style={{ maxWidth: 420, width: "100%", margin: "0 auto" }}>
            <h2 style={{ fontSize: 28, fontWeight: 700, color: "var(--color-text-primary)", margin: "0 0 8px" }}>
              欢迎回来
            </h2>
            <p style={{ fontSize: 15, color: "var(--color-text-tertiary)", margin: "0 0 32px" }}>
              选择身份入口并登录工作台
            </p>

            {/* 入口选择器 - 药丸式 */}
            <div style={{ display: "flex", gap: 8, marginBottom: 8 }}>
              {portals.map((portal) => {
                const Icon = portal.icon;
                const isActive = activePortal === portal.key;

                return (
                  <button
                    key={portal.key}
                    type="button"
                    onClick={() => setActivePortal(portal.key)}
                    style={{
                      flex: "1 1 0",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      gap: 6,
                      padding: "12px 8px",
                      border: isActive ? `1.5px solid ${portal.color}` : "1.5px solid var(--color-border)",
                      borderRadius: "var(--radius-lg)",
                      background: isActive ? `color-mix(in srgb, ${portal.color} 8%, var(--color-bg-card))` : "var(--color-bg-card)",
                      boxShadow: isActive ? `0 0 0 1px color-mix(in srgb, ${portal.color} 16%, transparent)` : "none",
                      cursor: "pointer",
                      transition: "all 0.25s ease",
                      whiteSpace: "nowrap",
                    }}
                  >
                    <Icon
                      className="h-5 w-5"
                      style={{ color: isActive ? portal.color : "var(--color-text-tertiary)" }}
                      aria-hidden="true"
                    />
                    <span
                      style={{
                        fontSize: 14,
                        fontWeight: isActive ? 600 : 500,
                        color: isActive ? portal.color : "var(--color-text-secondary)",
                      }}
                    >
                      {portal.label}
                    </span>
                  </button>
                );
              })}
            </div>

            {/* 当前入口描述 */}
            <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: "var(--color-text-tertiary)", marginBottom: 24, padding: "0 4px" }}>
              <span style={{ width: 6, height: 6, borderRadius: "50%", background: currentPortal.color, flexShrink: 0 }} />
              {currentPortal.description}
            </div>

            {/* 登录表单 */}
            <form onSubmit={handleSubmit}>
              <div style={{ marginBottom: 16 }}>
                <div style={{ position: "relative" }}>
                  <User
                    className="h-5 w-5"
                    style={{ position: "absolute", left: 16, top: "50%", transform: "translateY(-50%)", color: "var(--color-text-tertiary)" }}
                    aria-hidden="true"
                  />
                  <input
                    value={username}
                    onChange={(e) => { setUsername(e.target.value); setError(""); }}
                    className="agent-input"
                    placeholder="用户名"
                    autoComplete="username"
                    style={{ width: "100%", height: 48, paddingLeft: 44, paddingRight: 16, fontSize: 15 }}
                  />
                </div>
              </div>

              <div style={{ marginBottom: 16 }}>
                <div style={{ position: "relative" }}>
                  <KeyRound
                    className="h-5 w-5"
                    style={{ position: "absolute", left: 16, top: "50%", transform: "translateY(-50%)", color: "var(--color-text-tertiary)" }}
                    aria-hidden="true"
                  />
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => { setPassword(e.target.value); setError(""); }}
                    className="agent-input"
                    placeholder="密码"
                    autoComplete="current-password"
                    style={{ width: "100%", height: 48, paddingLeft: 44, paddingRight: 16, fontSize: 15 }}
                  />
                </div>
              </div>

              {/* 记住我 */}
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 24 }}>
                <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 14, color: "var(--color-text-secondary)", cursor: "pointer" }}>
                  <input
                    type="checkbox"
                    checked={rememberMe}
                    onChange={(e) => setRememberMe(e.target.checked)}
                    style={{ accentColor: "var(--color-primary)", width: 16, height: 16 }}
                  />
                  记住我
                </label>
                <button type="button" style={{ border: "none", background: "none", fontSize: 14, color: "var(--color-primary)", cursor: "pointer", padding: 0 }}>
                  忘记密码？
                </button>
              </div>

              {/* 错误提示 */}
              {error ? (
                <div style={{
                  padding: "10px 14px",
                  borderRadius: "var(--radius-md)",
                  border: "1px solid var(--color-danger)",
                  background: "var(--color-danger-bg)",
                  color: "var(--color-danger)",
                  fontSize: 13,
                  marginBottom: 16,
                }}>
                  {error}
                </div>
              ) : null}

              {/* 登录按钮 */}
              <button
                type="submit"
                disabled={loading}
                style={{
                  width: "100%",
                  height: 48,
                  borderRadius: "var(--radius-md)",
                  border: "none",
                  background: `linear-gradient(135deg, ${currentPortal.color}, ${currentPortal.color}dd)`,
                  color: "#fff",
                  fontSize: 16,
                  fontWeight: 600,
                  cursor: loading ? "wait" : "pointer",
                  opacity: loading ? 0.7 : 1,
                  boxShadow: `0 4px 14px ${currentPortal.color}40`,
                  transition: "all 0.3s ease",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  gap: 8,
                }}
              >
                {loading ? "登录中…" : `以${currentPortal.label}身份登录`}
              </button>
            </form>

            {/* SSO 占位 */}
            <div style={{ marginTop: 24, textAlign: "center" }}>
              <p style={{ fontSize: 13, color: "var(--color-text-tertiary)" }}>
                企业 SSO 登录即将支持
              </p>
            </div>

            {/* 页脚 */}
            <div style={{ textAlign: "center", marginTop: 32, color: "var(--color-text-tertiary)", fontSize: 13 }}>
              Agentum © 2025
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
