import { useCallback, useEffect, useRef, useState } from "react";
import {
  Activity,
  Bell,
  GitBranch,
  IdCard,
  KeyRound,
  LayoutDashboard,
  Library,
  LogOut,
  Mail,
  PanelLeft,
  Save,
  Settings,
  ShieldCheck,
  User,
  UserRoundCog,
  X,
} from "lucide-react";
import { message, Segmented } from "antd";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { AgentumMark } from "../components/brand/AgentumMark";
import { SysModalMask } from "../components/common/SysModalMask";
import { SysPasswordInput } from "../components/common/SysPasswordInput";
import { useAuthStore } from "../stores/authStore";
import { paths, surfaceFromPath, surfaceNavPath, type SurfaceKey } from "../routes/paths";
import { isDarkTheme } from "../utils/theme";

const ICON_MAP = {
  LayoutDashboard,
  GitBranch,
  Library,
  Activity,
  ShieldCheck,
  Settings,
} as const;

type AccountSettingsTabKey = "overview" | "profile" | "security";

const accountSettingsTabs: Array<{
  key: AccountSettingsTabKey;
  label: string;
  icon: typeof ShieldCheck;
}> = [
  { key: "overview", label: "总览", icon: ShieldCheck },
  { key: "profile", label: "基础资料", icon: IdCard },
  { key: "security", label: "账号安全", icon: KeyRound },
];

export function AppLayout() {
  const menus = useAuthStore((state) => state.menus);
  const themeMode = useAuthStore((state) => state.themeMode);
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const updateMyProfile = useAuthStore((state) => state.updateMyProfile);
  const changeMyPassword = useAuthStore((state) => state.changeMyPassword);
  const location = useLocation();
  const navigate = useNavigate();
  const isDarkMode = isDarkTheme(themeMode);
  const activeSurface = surfaceFromPath(location.pathname);
  const isRunDetail = location.pathname.includes("/workbench/runs/");
  const isWorkflowEditor = location.pathname.includes("/designer/workflows/");

  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isSidebarTransitioning, setIsSidebarTransitioning] = useState(false);
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [profileModalOpen, setProfileModalOpen] = useState(false);
  const [accountSettingsTab, setAccountSettingsTab] = useState<AccountSettingsTabKey>("overview");
  const [profileSubmitting, setProfileSubmitting] = useState(false);
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const [profileDraft, setProfileDraft] = useState({ displayName: user?.displayName ?? "", email: user?.email ?? "" });
  const [passwordDraft, setPasswordDraft] = useState({ currentPassword: "", newPassword: "", confirmPassword: "" });
  const [messageApi, messageContextHolder] = message.useMessage();
  const sidebarTransitionTimer = useRef<number | null>(null);
  const accountMenuRef = useRef<HTMLDivElement | null>(null);
  const isSidebarCompact = isSidebarCollapsed || isSidebarTransitioning;
  const showSidebarText = !isSidebarCompact;
  const avatarText = getAvatarText(user?.displayName || user?.username || "A");
  const accountSettingsSegmentedOptions = accountSettingsTabs.map((tab) => {
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

  useEffect(() => () => {
    if (sidebarTransitionTimer.current !== null) {
      window.clearTimeout(sidebarTransitionTimer.current);
    }
  }, []);

  useEffect(() => {
    setProfileDraft({ displayName: user?.displayName ?? "", email: user?.email ?? "" });
  }, [user?.displayName, user?.email]);

  useEffect(() => {
    if (!accountMenuOpen) return;

    const handlePointerDown = (event: MouseEvent) => {
      if (accountMenuRef.current?.contains(event.target as Node)) return;
      setAccountMenuOpen(false);
    };

    window.addEventListener("mousedown", handlePointerDown);
    return () => window.removeEventListener("mousedown", handlePointerDown);
  }, [accountMenuOpen]);

  const handleToggleSidebar = useCallback(() => {
    if (sidebarTransitionTimer.current !== null) {
      window.clearTimeout(sidebarTransitionTimer.current);
    }
    setIsSidebarTransitioning(true);
    setIsSidebarCollapsed((current) => !current);
    sidebarTransitionTimer.current = window.setTimeout(() => {
      setIsSidebarTransitioning(false);
      sidebarTransitionTimer.current = null;
    }, 320);
  }, []);

  const openProfileSettings = useCallback(() => {
    setProfileDraft({ displayName: user?.displayName ?? "", email: user?.email ?? "" });
    setPasswordDraft({ currentPassword: "", newPassword: "", confirmPassword: "" });
    setAccountSettingsTab("overview");
    setProfileModalOpen(true);
    setAccountMenuOpen(false);
  }, [user?.displayName, user?.email]);

  async function handleSaveProfile() {
    if (!profileDraft.displayName.trim()) {
      messageApi.warning("请输入姓名");
      return;
    }
    setProfileSubmitting(true);
    try {
      const result = await updateMyProfile({
        displayName: profileDraft.displayName.trim(),
        email: profileDraft.email.trim(),
      });
      if (!result.success) {
        messageApi.error(result.message ?? "个人资料保存失败");
        return;
      }
      messageApi.success("个人资料已更新");
    } finally {
      setProfileSubmitting(false);
    }
  }

  async function handleChangePassword() {
    if (!passwordDraft.currentPassword || !passwordDraft.newPassword) {
      messageApi.warning("请输入当前密码和新密码");
      return;
    }
    if (passwordDraft.newPassword.length < 8) {
      messageApi.warning("新密码至少 8 位");
      return;
    }
    if (passwordDraft.newPassword !== passwordDraft.confirmPassword) {
      messageApi.warning("两次输入的新密码不一致");
      return;
    }

    setPasswordSubmitting(true);
    try {
      const result = await changeMyPassword({
        currentPassword: passwordDraft.currentPassword,
        newPassword: passwordDraft.newPassword,
      });
      if (!result.success) {
        messageApi.error(result.message ?? "密码修改失败");
        return;
      }
      messageApi.success("密码已修改，请重新登录");
      setProfileModalOpen(false);
      window.setTimeout(() => navigate(paths.login, { replace: true }), 300);
    } finally {
      setPasswordSubmitting(false);
    }
  }

  async function handleLogout() {
    setAccountMenuOpen(false);
    await logout();
  }

  return (
    <main className={`min-h-screen bg-[var(--color-bg-page)] text-[var(--color-text-primary)] transition-colors duration-300 ${isDarkMode ? "dark" : ""}`}>
      {messageContextHolder}
      <div className="flex min-h-screen">
        <aside
          className={`workbench-sidebar hidden shrink-0 sticky top-0 z-20 h-screen max-h-screen border-r border-[var(--color-sidebar-border)] bg-[var(--color-bg-sidebar)] text-[var(--color-text-sidebar)] transition-[width,background-color] duration-300 lg:flex lg:flex-col ${isSidebarCollapsed ? "workbench-sidebar--collapsed w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"}`}
        >
          <div
            className={`workbench-sidebar-header shrink-0 ${isSidebarCollapsed ? "workbench-sidebar-header--compact" : "workbench-sidebar-header--expanded"}`}
          >
            {isSidebarCollapsed ? (
              <>
                <button
                  type="button"
                  onClick={handleToggleSidebar}
                  className="workbench-sidebar-compact-brand"
                  aria-label="打开边栏"
                >
                  <span className="workbench-sidebar-mark-slot overflow-hidden rounded-lg shadow-sm">
                    <AgentumMark className="workbench-sidebar-mark-logo h-9 w-9 shrink-0 object-contain" />
                    <span className="workbench-sidebar-mark-toggle" aria-hidden="true">
                      <PanelLeft className="h-4 w-4" />
                    </span>
                  </span>
                </button>
                <span className="workbench-sidebar-expand-hint" aria-hidden="true">
                  打开边栏
                </span>
              </>
            ) : (
              <>
                <div className="workbench-sidebar-brand">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-lg shadow-sm">
                    <AgentumMark className="h-9 w-9 shrink-0 object-contain" />
                  </div>
                  {showSidebarText ? (
                    <div className="workbench-sidebar-text workbench-sidebar-text--visible">
                      <p className="text-lg font-bold text-[var(--color-sidebar-logo-text)]">Agentum</p>
                    </div>
                  ) : null}
                </div>
                <button
                  type="button"
                  onClick={handleToggleSidebar}
                  className="workbench-sidebar-toggle workbench-sidebar-hint-below"
                  aria-label="关闭边栏"
                  data-hint="关闭边栏"
                >
                  <PanelLeft className="h-4 w-4" aria-hidden="true" />
                </button>
              </>
            )}
          </div>

          <nav className="flex-1 overflow-y-auto min-h-0 space-y-1 px-3 py-3" aria-label="主导航">
            <p className={`px-3 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--color-sidebar-section-title)] ${showSidebarText ? "" : "sr-only"}`}>
              主工作区
            </p>
            {menus.map((menuItem) => {
              const Icon = ICON_MAP[menuItem.icon as keyof typeof ICON_MAP] ?? LayoutDashboard;
              const surfaceKey = menuItem.key as SurfaceKey;
              const navPath = surfaceNavPath(surfaceKey);

              return (
                <NavLink
                  key={menuItem.key}
                  to={navPath}
                  title={menuItem.description}
                  className={({ isActive }) =>
                    `relative flex w-full items-center rounded-lg text-left transition-all duration-200 ${isSidebarCompact ? "h-11 justify-center px-0" : "gap-3 px-3 py-2.5"} ${
                      isActive || activeSurface === surfaceKey
                        ? "bg-[var(--color-bg-sidebar-active)] font-medium text-[var(--color-text-sidebar-active)]"
                        : "text-[var(--color-text-sidebar)] hover:bg-[var(--color-bg-sidebar-hover)] hover:text-[var(--color-text-primary)]"
                    }`
                  }
                >
                  {({ isActive }) => (
                    <>
                      <Icon className={`h-5 w-5 shrink-0 ${isActive || activeSurface === surfaceKey ? "text-[var(--color-primary)]" : ""}`} aria-hidden="true" />
                      <span className={`workbench-sidebar-text min-w-0 ${showSidebarText ? "workbench-sidebar-text--visible" : ""}`} aria-hidden={!showSidebarText}>
                        <span className="block text-sm font-medium">{menuItem.label}</span>
                        <span className="block text-xs text-[var(--color-text-tertiary)]">{menuItem.description}</span>
                      </span>
                      {isActive || activeSurface === surfaceKey ? (
                        <span className="absolute right-0 top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-l bg-[var(--color-primary)]" />
                      ) : null}
                    </>
                  )}
                </NavLink>
              );
            })}
          </nav>

          <div ref={accountMenuRef} className={`workbench-account-area border-t border-[var(--color-border-light)] p-3 ${isSidebarCompact ? "flex justify-center" : ""}`}>
            {isSidebarCompact ? (
              <button
                type="button"
                onClick={() => setAccountMenuOpen((open) => !open)}
                className="workbench-account-avatar-btn"
                title="账号菜单"
                aria-label="打开账号菜单"
              >
                <AccountAvatar avatarUrl={user?.avatar} text={avatarText} />
              </button>
            ) : (
              <button
                type="button"
                className="workbench-account-expanded-btn"
                onClick={() => setAccountMenuOpen((open) => !open)}
                aria-label="打开账号菜单"
              >
                <AccountAvatar avatarUrl={user?.avatar} text={avatarText} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">{user?.displayName ?? "未登录"}</p>
                  <p className="truncate text-xs text-[var(--color-text-tertiary)]">{user?.organization ?? ""}</p>
                </div>
              </button>
            )}
            {accountMenuOpen ? (
              <div className={`workbench-account-menu ${isSidebarCompact ? "workbench-account-menu--compact" : ""}`}>
                <button type="button" className="workbench-account-menu-item" onClick={openProfileSettings}>
                  <UserRoundCog className="h-4 w-4" aria-hidden="true" />
                  <span>个人设置</span>
                </button>
                <button type="button" className="workbench-account-menu-item" onClick={() => { setAccountMenuOpen(false); messageApi.info("消息中心功能即将开放"); }}>
                  <Bell className="h-4 w-4" aria-hidden="true" />
                  <span>消息中心</span>
                </button>
                <button type="button" className="workbench-account-menu-item workbench-account-menu-item--danger" onClick={() => void handleLogout()}>
                  <LogOut className="h-4 w-4" aria-hidden="true" />
                  <span>退出登录</span>
                </button>
              </div>
            ) : null}
          </div>
        </aside>

        <section className={`min-w-0 flex-1${isRunDetail || isWorkflowEditor ? " overflow-hidden" : ""}`}>
          <Outlet />
        </section>
      </div>
      {profileModalOpen ? (
        <SysModalMask onClose={() => setProfileModalOpen(false)}>
          <div className="sys-modal account-settings-modal" style={{ maxWidth: 680 }}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">个人设置</span>
              <button className="sys-modal-close" onClick={() => setProfileModalOpen(false)}><X size={18} /></button>
            </div>
            <div className="account-settings-tabs-wrap" aria-label="个人设置功能区">
              <div className="system-mgmt-segmented-scroll account-settings-segmented-scroll">
                <Segmented<AccountSettingsTabKey>
                  value={accountSettingsTab}
                  onChange={setAccountSettingsTab}
                  options={accountSettingsSegmentedOptions}
                  className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented account-settings-segmented"
                />
              </div>
            </div>
            <div className="sys-modal-body account-settings-body">
              {accountSettingsTab === "overview" ? (
                <div className="account-settings-panel">
                  <div className="account-settings-summary">
                    <AccountAvatar avatarUrl={user?.avatar} text={avatarText} large />
                    <div>
                      <strong>{user?.displayName || "未登录用户"}</strong>
                      <span>{user?.username || "-"}</span>
                    </div>
                  </div>
                  <div className="account-settings-grid">
                    <div className="sys-readonly-field"><span>当前入口</span><strong>{formatRoleLabel(user?.role)}</strong></div>
                    <div className="sys-readonly-field"><span>当前租户</span><strong>{user?.tenantName || "平台管理"}</strong></div>
                    <div className="sys-readonly-field"><span>租户编码</span><strong>{user?.tenantCode || "SYSTEM"}</strong></div>
                    <div className="sys-readonly-field"><span>最近登录</span><strong>{formatAccountDate(user?.lastLoginAt)}</strong></div>
                  </div>
                  <div className="account-settings-quick-actions">
                    <button type="button" className="sys-btn sys-btn--default" onClick={() => setAccountSettingsTab("profile")}>
                      <IdCard size={14} /> 修改资料
                    </button>
                    <button type="button" className="sys-btn sys-btn--default" onClick={() => setAccountSettingsTab("security")}>
                      <KeyRound size={14} /> 修改密码
                    </button>
                  </div>
                </div>
              ) : null}

              {accountSettingsTab === "profile" ? (
                <div className="account-settings-panel">
                  <div className="sys-config-group">
                    <div className="sys-config-group-title">基础资料</div>
                    <div className="sys-field-row">
                      <div className="sys-field">
                        <label className="sys-field-label sys-field-label--required">姓名</label>
                        <div className="sys-field-input-wrap">
                          <User size={16} className="sys-field-prefix" />
                          <input
                            className="sys-field-input"
                            value={profileDraft.displayName}
                            maxLength={100}
                            onChange={(event) => setProfileDraft((draft) => ({ ...draft, displayName: event.target.value }))}
                          />
                        </div>
                      </div>
                      <div className="sys-field">
                        <label className="sys-field-label">邮箱</label>
                        <div className="sys-field-input-wrap">
                          <Mail size={16} className="sys-field-prefix" />
                          <input
                            className="sys-field-input"
                            value={profileDraft.email}
                            maxLength={255}
                            placeholder="name@example.com"
                            onChange={(event) => setProfileDraft((draft) => ({ ...draft, email: event.target.value }))}
                          />
                        </div>
                      </div>
                    </div>
                    <div className="sys-config-actions">
                      <button className="sys-btn sys-btn--primary" disabled={profileSubmitting} onClick={() => void handleSaveProfile()}>
                        <Save size={14} /> 保存资料
                      </button>
                    </div>
                  </div>
                </div>
              ) : null}

              {accountSettingsTab === "security" ? (
                <div className="account-settings-panel">
                  <div className="sys-config-group">
                    <div className="sys-config-group-title">修改密码</div>
                    <div className="sys-field">
                      <label className="sys-field-label sys-field-label--required">当前密码</label>
                      <SysPasswordInput
                        prefixIcon={<KeyRound size={16} className="sys-field-prefix" />}
                        value={passwordDraft.currentPassword}
                        autoComplete="current-password"
                        placeholder="请输入当前密码"
                        onChange={(event) => setPasswordDraft((draft) => ({ ...draft, currentPassword: event.target.value }))}
                      />
                    </div>
                    <div className="sys-field-row">
                      <div className="sys-field">
                        <label className="sys-field-label sys-field-label--required">新密码</label>
                        <SysPasswordInput
                          prefixIcon={<KeyRound size={16} className="sys-field-prefix" />}
                          value={passwordDraft.newPassword}
                          autoComplete="new-password"
                          placeholder="至少 8 位"
                          onChange={(event) => setPasswordDraft((draft) => ({ ...draft, newPassword: event.target.value }))}
                        />
                      </div>
                      <div className="sys-field">
                        <label className="sys-field-label sys-field-label--required">确认新密码</label>
                        <SysPasswordInput
                          prefixIcon={<KeyRound size={16} className="sys-field-prefix" />}
                          value={passwordDraft.confirmPassword}
                          autoComplete="new-password"
                          placeholder="再次输入新密码"
                          onChange={(event) => setPasswordDraft((draft) => ({ ...draft, confirmPassword: event.target.value }))}
                        />
                      </div>
                    </div>
                    <p className="sys-field-hint">密码修改成功后会立即退出登录，请使用新密码重新进入工作台。</p>
                    <div className="sys-config-actions">
                      <button className="sys-btn sys-btn--default" disabled={passwordSubmitting} onClick={() => void handleChangePassword()}>
                        <KeyRound size={14} /> 修改密码并退出
                      </button>
                    </div>
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </SysModalMask>
      ) : null}
    </main>
  );
}

function AccountAvatar({ avatarUrl, text, large = false }: { avatarUrl?: string; text: string; large?: boolean }) {
  const sizeClass = large ? "account-avatar account-avatar--large" : "account-avatar";
  if (avatarUrl) {
    return <img src={avatarUrl} alt="用户头像" className={sizeClass} />;
  }
  return <span className={sizeClass}>{text}</span>;
}

function getAvatarText(value: string): string {
  const normalized = value.trim();
  return (normalized ? normalized.slice(0, 1) : "A").toUpperCase();
}

function formatRoleLabel(role?: string): string {
  if (role === "system_admin") return "系统管理";
  if (role === "tenant_admin") return "租户管理";
  if (role === "business") return "业务用户";
  return role || "-";
}

function formatAccountDate(value?: string): string {
  if (!value) return "暂无记录";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}
