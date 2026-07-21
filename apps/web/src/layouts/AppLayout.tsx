import {
  Activity,
  Bell,
  Building2,
  Check,
  CheckCircle2,
  Eye,
  GitBranch,
  IdCard,
  Inbox,
  KeyRound,
  LayoutDashboard,
  Library,
  Loader2,
  LogOut,
  Mail,
  Megaphone,
  Moon,
  PanelLeft,
  PencilLine,
  Save,
  Settings,
  ShieldCheck,
  Sparkles,
  Sun,
  User,
  UserRoundCog,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Drawer, Empty, Pagination, Segmented, message } from "antd";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { AgentumMark } from "../components/brand/AgentumMark";
import { SysPasswordInput } from "../components/common/SysPasswordInput";
import { MarkdownRenderer } from "../components/runtime/MarkdownRenderer";
import { AgentumApiError, notificationApi } from "../services/apiClient";
import { useAuthStore } from "../stores/authStore";
import { prefersReducedMotion } from "../motion/prefersReducedMotion";
import { paths, surfaceFromPath, surfaceNavPath, type SurfaceKey } from "../routes/paths";
import { getThemedDrawerRootClassName, isDarkTheme } from "../utils/theme";
import type { NotificationRow, NotificationStatusFilter } from "../types/notification";
import type { RoleInfo, ThemeMode } from "../types/auth";
import gsap from "gsap";

const ICON_MAP = {
  LayoutDashboard,
  GitBranch,
  Library,
  Activity,
  ShieldCheck,
  Settings,
} as const;

type AccountSettingsTabKey = "overview" | "profile" | "security";
type NotificationCenterTabKey = "inbox" | "publish";
type AnnouncementEditorMode = "edit" | "preview";

const NOTIFICATION_PAGE_SIZE = 8;
const ACCOUNT_DRAWER_WIDTH = 620;

const accountThemeOptions: Array<{ mode: ThemeMode; label: string; icon: typeof Sun }> = [
  { mode: "light", label: "浅色", icon: Sun },
  { mode: "dark", label: "深色", icon: Moon },
  { mode: "warm", label: "暖纸", icon: Sparkles },
];

const accountRoleIcons = {
  system_admin: Settings,
  tenant_admin: ShieldCheck,
  business: LayoutDashboard,
} as const;

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
  const setThemeMode = useAuthStore((state) => state.setThemeMode);
  const user = useAuthStore((state) => state.user);
  const roles = useAuthStore((state) => state.roles);
  const activeRole = useAuthStore((state) => state.activeRole);
  const switchRole = useAuthStore((state) => state.switchRole);
  const logout = useAuthStore((state) => state.logout);
  const token = useAuthStore((state) => state.token);
  const updateMyProfile = useAuthStore((state) => state.updateMyProfile);
  const changeMyPassword = useAuthStore((state) => state.changeMyPassword);
  const location = useLocation();
  const navigate = useNavigate();
  const isDarkMode = isDarkTheme(themeMode);
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode);
  const activeSurface = surfaceFromPath(location.pathname);
  const isRunDetail = location.pathname.includes("/workbench/runs/");
  const isWorkflowEditor = location.pathname.includes("/designer/workflows/");

  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [switchingRoleId, setSwitchingRoleId] = useState<string | null>(null);
  const [profileDrawerOpen, setProfileDrawerOpen] = useState(false);
  const [accountSettingsTab, setAccountSettingsTab] = useState<AccountSettingsTabKey>("overview");
  const [profileSubmitting, setProfileSubmitting] = useState(false);
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const [profileDraft, setProfileDraft] = useState({ displayName: user?.displayName ?? "", email: user?.email ?? "" });
  const [passwordDraft, setPasswordDraft] = useState({ currentPassword: "", newPassword: "", confirmPassword: "" });
  const [notificationDrawerOpen, setNotificationDrawerOpen] = useState(false);
  const [notificationCenterTab, setNotificationCenterTab] = useState<NotificationCenterTabKey>("inbox");
  const [notificationStatus, setNotificationStatus] = useState<NotificationStatusFilter>("all");
  const [notificationPage, setNotificationPage] = useState(1);
  const [notifications, setNotifications] = useState<NotificationRow[]>([]);
  const [notificationTotal, setNotificationTotal] = useState(0);
  const [notificationLoading, setNotificationLoading] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [announcementDraft, setAnnouncementDraft] = useState({ title: "", contentMarkdown: "" });
  const [announcementEditorMode, setAnnouncementEditorMode] = useState<AnnouncementEditorMode>("edit");
  const [announcementPublishing, setAnnouncementPublishing] = useState(false);
  const [messageApi, messageContextHolder] = message.useMessage();
  const accountMenuRef = useRef<HTMLDivElement | null>(null);
  const sidebarBrandRef = useRef<HTMLDivElement | null>(null);
  // 侧栏只保留 collapsed 一个状态：宽度与文字都由 CSS 同步过渡，避免超时状态机打架。
  const isSidebarCompact = isSidebarCollapsed;
  const showSidebarText = !isSidebarCollapsed;
  const avatarText = getAvatarText(user?.displayName || user?.username || "A");
  const canPublishAnnouncement = user?.role === "system_admin" || user?.role === "tenant_admin";
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
  const notificationCenterTabOptions = useMemo(
    () => [
      {
        value: "inbox" as const,
        label: (
          <span className="login-portal-option">
            <Inbox className="login-portal-option-icon" aria-hidden="true" />
            <span>我的消息</span>
          </span>
        ),
      },
      {
        value: "publish" as const,
        label: (
          <span className="login-portal-option">
            <Megaphone className="login-portal-option-icon" aria-hidden="true" />
            <span>发布公告</span>
          </span>
        ),
      },
    ],
    [],
  );
  const notificationStatusOptions = useMemo(
    () => [
      { value: "all" as const, label: "全部消息" },
      { value: "unread" as const, label: "未读消息" },
      { value: "read" as const, label: "已读消息" },
    ],
    [],
  );

  useEffect(() => {
    setProfileDraft({ displayName: user?.displayName ?? "", email: user?.email ?? "" });
  }, [user?.displayName, user?.email]);

  const loadUnreadCount = useCallback(async () => {
    if (!token) {
      setUnreadCount(0);
      return;
    }
    try {
      const data = await notificationApi.unreadCount(token);
      setUnreadCount(data.unreadCount);
    } catch (error) {
      console.warn("[notification] 未读消息数加载失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
    }
  }, [token]);

  const loadNotifications = useCallback(async (page = notificationPage, status = notificationStatus) => {
    if (!token) {
      setNotifications([]);
      setNotificationTotal(0);
      return;
    }
    setNotificationLoading(true);
    try {
      const data = await notificationApi.list(token, status, page, NOTIFICATION_PAGE_SIZE);
      setNotifications(data.items);
      setNotificationTotal(data.total);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "消息加载失败";
      messageApi.error(reason);
      setNotifications([]);
      setNotificationTotal(0);
    } finally {
      setNotificationLoading(false);
    }
  }, [messageApi, notificationPage, notificationStatus, token]);

  useEffect(() => {
    void loadUnreadCount();
  }, [loadUnreadCount]);

  useEffect(() => {
    if (!notificationDrawerOpen || notificationCenterTab !== "inbox") {
      return;
    }
    void loadNotifications(notificationPage, notificationStatus);
  }, [loadNotifications, notificationCenterTab, notificationDrawerOpen, notificationPage, notificationStatus]);

  useEffect(() => {
    if (!accountMenuOpen) return;

    const handlePointerDown = (event: MouseEvent) => {
      if (accountMenuRef.current?.contains(event.target as Node)) return;
      setAccountMenuOpen(false);
    };

    window.addEventListener("mousedown", handlePointerDown);
    return () => window.removeEventListener("mousedown", handlePointerDown);
  }, [accountMenuOpen]);

  // 侧栏品牌区入场：SSO 用户每天都能看到，不依赖登录页。
  useEffect(() => {
    const el = sidebarBrandRef.current;
    if (!el || prefersReducedMotion()) {
      return;
    }
    gsap.fromTo(
      el,
      { rotateY: -40, opacity: 0, transformPerspective: 600 },
      { rotateY: 0, opacity: 1, duration: 0.5, ease: "power2.out" },
    );
  }, []);

  const handleToggleSidebar = useCallback(() => {
    setIsSidebarCollapsed((current) => !current);
  }, []);

  const openProfileSettings = useCallback(() => {
    setProfileDraft({ displayName: user?.displayName ?? "", email: user?.email ?? "" });
    setPasswordDraft({ currentPassword: "", newPassword: "", confirmPassword: "" });
    setAccountSettingsTab("overview");
    setProfileDrawerOpen(true);
    setAccountMenuOpen(false);
  }, [user?.displayName, user?.email]);

  const openNotificationCenter = useCallback(() => {
    setAccountMenuOpen(false);
    setNotificationCenterTab("inbox");
    setNotificationDrawerOpen(true);
    setNotificationPage(1);
  }, []);

  async function handleMarkNotificationRead(row: NotificationRow) {
    if (!token || !row.unread) {
      return;
    }
    try {
      await notificationApi.markRead(token, row.id);
      setNotifications((items) => items.map((item) => item.id === row.id ? { ...item, unread: false, readAt: new Date().toISOString() } : item));
      setUnreadCount((count) => Math.max(0, count - 1));
    } catch (error) {
      console.warn("[notification] 消息标记已读失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
    }
  }

  async function handleMarkAllNotificationsRead() {
    if (!token) {
      return;
    }
    try {
      const data = await notificationApi.markAllRead(token);
      setUnreadCount(data.unreadCount);
      await loadNotifications(notificationPage, notificationStatus);
      messageApi.success("消息已全部标记为已读");
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "全部已读失败";
      messageApi.error(reason);
    }
  }

  async function handlePublishAnnouncement() {
    if (!token || !user || !canPublishAnnouncement) {
      return;
    }
    const title = announcementDraft.title.trim();
    const contentMarkdown = announcementDraft.contentMarkdown.trim();
    if (!title) {
      messageApi.warning("请输入公告标题");
      return;
    }
    if (!contentMarkdown) {
      messageApi.warning("请输入公告内容");
      return;
    }
    setAnnouncementPublishing(true);
    try {
      await notificationApi.publishAnnouncement(token, {
        scope: user.role === "system_admin" ? "global" : "tenant",
        tenantId: user.role === "tenant_admin" ? user.tenantId : null,
        title,
        contentMarkdown,
      });
      setAnnouncementDraft({ title: "", contentMarkdown: "" });
      setAnnouncementEditorMode("edit");
      messageApi.success("公告已发布");
      setNotificationCenterTab("inbox");
      setNotificationStatus("all");
      setNotificationPage(1);
      await loadNotifications(1, "all");
      await loadUnreadCount();
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "公告发布失败";
      messageApi.error(reason);
    } finally {
      setAnnouncementPublishing(false);
    }
  }

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
      setProfileDrawerOpen(false);
      window.setTimeout(() => navigate(paths.login, { replace: true }), 300);
    } finally {
      setPasswordSubmitting(false);
    }
  }

  async function handleLogout() {
    setAccountMenuOpen(false);
    await logout();
  }

  async function handleSwitchRole(role: RoleInfo) {
    if (role.id === activeRole?.id || switchingRoleId) {
      return;
    }

    // 角色切换会重签登录上下文并刷新左侧菜单，账号菜单先保持打开，便于失败时展示明确反馈。
    setSwitchingRoleId(role.id);
    const result = await switchRole(role.id);
    setSwitchingRoleId(null);
    if (!result.success) {
      messageApi.error(result.message ?? "角色切换失败，请稍后重试");
      return;
    }
    setAccountMenuOpen(false);
  }

  return (
    <main className={`min-h-screen bg-[var(--color-bg-page)] text-[var(--color-text-primary)] transition-colors duration-300 ${isDarkMode ? "dark" : ""}`}>
      {messageContextHolder}
      <div className="flex min-h-screen">
        <aside
          className={`workbench-sidebar hidden shrink-0 sticky top-0 z-20 h-screen max-h-screen border-r border-[var(--color-sidebar-border)] bg-[var(--color-bg-sidebar)] text-[var(--color-text-sidebar)] transition-[width,background-color] duration-300 ease-out lg:flex lg:flex-col ${isSidebarCollapsed ? "workbench-sidebar--collapsed w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"}`}
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
                <div ref={sidebarBrandRef} className="workbench-sidebar-brand" data-motion="sidebar-brand">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-lg shadow-sm">
                    <AgentumMark className="h-9 w-9 shrink-0 object-contain" />
                  </div>
                  <div className={`workbench-sidebar-text ${showSidebarText ? "workbench-sidebar-text--visible" : ""}`}>
                    <p className="text-lg font-bold text-[var(--color-sidebar-logo-text)]">Agentum</p>
                  </div>
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
                  data-nav-key={menuItem.key}
                  className={({ isActive }) =>
                    `relative flex w-full items-center rounded-lg text-left transition-all duration-200 ${isSidebarCompact ? "h-11 justify-center px-0" : "gap-3 px-3 py-2.5"} ${
                      isActive || activeSurface === surfaceKey
                        ? "bg-[var(--color-bg-sidebar-active)] font-medium text-[var(--color-text-sidebar-active)]"
                        : "text-[var(--color-text-sidebar)] hover:bg-[var(--color-bg-sidebar-hover)] hover:text-[var(--color-text-primary)]"
                    }`
                  }
                >
                  {({ isActive }) => {
                    const active = isActive || activeSurface === surfaceKey;
                    return (
                      <>
                        <span
                          key={`${menuItem.key}-${active ? "on" : "off"}`}
                          className={`workbench-nav-icon ${active ? "workbench-nav-icon--pop" : ""}`}
                          aria-hidden="true"
                        >
                          <Icon
                            className={`h-5 w-5 shrink-0 ${active ? "text-[var(--color-primary)]" : ""}`}
                          />
                        </span>
                        <span className={`workbench-sidebar-text min-w-0 ${showSidebarText ? "workbench-sidebar-text--visible" : ""}`} aria-hidden={!showSidebarText}>
                          <span className="block text-sm font-medium">{menuItem.label}</span>
                          <span className="block text-xs text-[var(--color-text-tertiary)]">{menuItem.description}</span>
                        </span>
                        <span
                          className={`workbench-nav-indicator ${active ? "workbench-nav-indicator--active" : ""}`}
                          aria-hidden="true"
                        />
                      </>
                    );
                  }}
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
                <AccountAvatarWithBadge avatarUrl={user?.avatar} text={avatarText} unreadCount={unreadCount} />
              </button>
            ) : (
              <button
                type="button"
                className="workbench-account-expanded-btn"
                onClick={() => setAccountMenuOpen((open) => !open)}
                aria-label="打开账号菜单"
              >
                <AccountAvatarWithBadge avatarUrl={user?.avatar} text={avatarText} unreadCount={unreadCount} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">{user?.displayName ?? "未登录"}</p>
                  <p className="truncate text-xs text-[var(--color-text-tertiary)]">{user?.organization ?? ""}</p>
                </div>
              </button>
            )}
            {accountMenuOpen ? (
              <div className={`workbench-account-menu ${isSidebarCompact ? "workbench-account-menu--compact" : ""}`}>
                <section className="workbench-account-menu-section" aria-labelledby="account-theme-title">
                  <p id="account-theme-title" className="workbench-account-menu-title">切换主题</p>
                  <div className="workbench-account-theme-options" role="group" aria-label="切换主题">
                    {accountThemeOptions.map((option) => {
                      const Icon = option.icon;
                      const active = themeMode === option.mode;
                      return (
                        <button
                          key={option.mode}
                          type="button"
                          className={`workbench-account-theme-option ${active ? "workbench-account-theme-option--active" : ""}`}
                          onClick={() => setThemeMode(option.mode)}
                          aria-pressed={active}
                        >
                          <Icon className="h-4 w-4" aria-hidden="true" />
                          <span>{option.label}</span>
                        </button>
                      );
                    })}
                  </div>
                </section>
                {roles.length > 1 ? (
                  <section className="workbench-account-menu-section" aria-labelledby="account-role-title">
                    <p id="account-role-title" className="workbench-account-menu-title">切换角色</p>
                    <div className="workbench-account-role-list" role="menu" aria-label="切换角色">
                      {roles.map((role) => {
                        const active = role.id === activeRole?.id;
                        const RoleIcon = accountRoleIcons[role.role] ?? LayoutDashboard;
                        const switching = role.id === switchingRoleId;
                        return (
                          <button
                            key={role.id}
                            type="button"
                            role="menuitemradio"
                            aria-checked={active}
                            className={`workbench-account-role-option ${active ? "workbench-account-role-option--active" : ""}`}
                            disabled={active || switchingRoleId !== null}
                            onClick={() => void handleSwitchRole(role)}
                          >
                            <span className="workbench-account-role-icon">
                              {switching ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <RoleIcon className="h-4 w-4" aria-hidden="true" />}
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-[13px] font-semibold">{role.label}</span>
                              {role.tenantName ? (
                                <span className="workbench-account-role-tenant">
                                  <Building2 className="h-3 w-3 shrink-0" aria-hidden="true" />
                                  <span className="truncate">{role.tenantName}</span>
                                </span>
                              ) : null}
                            </span>
                            {active ? <Check className="h-4 w-4 shrink-0" aria-hidden="true" /> : null}
                          </button>
                        );
                      })}
                    </div>
                  </section>
                ) : null}
                <div className="workbench-account-menu-divider" />
                <button type="button" className="workbench-account-menu-item" onClick={openProfileSettings}>
                  <UserRoundCog className="h-4 w-4" aria-hidden="true" />
                  <span>个人设置</span>
                </button>
                <button type="button" className="workbench-account-menu-item" onClick={openNotificationCenter}>
                  <Bell className="h-4 w-4" aria-hidden="true" />
                  <span>消息中心</span>
                  {unreadCount > 0 ? <span className="account-menu-unread-count">{formatUnreadCount(unreadCount)}</span> : null}
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
      <Drawer
        title="个人设置"
        width={ACCOUNT_DRAWER_WIDTH}
        open={profileDrawerOpen}
        onClose={() => setProfileDrawerOpen(false)}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section account-settings-drawer">
          <div className="system-mgmt-module-switch account-drawer-module-switch">
            <div className="system-mgmt-segmented-scroll">
              <Segmented<AccountSettingsTabKey>
                aria-label="个人设置功能区"
                value={accountSettingsTab}
                onChange={setAccountSettingsTab}
                options={accountSettingsSegmentedOptions}
                className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
              />
            </div>
          </div>
          <div className="account-settings-body account-settings-body--drawer">
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
      </Drawer>
      <Drawer
        title="消息中心"
        width={ACCOUNT_DRAWER_WIDTH}
        open={notificationDrawerOpen}
        onClose={() => setNotificationDrawerOpen(false)}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section notification-center">
          {canPublishAnnouncement ? (
            <div className="system-mgmt-module-switch account-drawer-module-switch">
              <div className="system-mgmt-segmented-scroll">
                <Segmented<NotificationCenterTabKey>
                  aria-label="消息中心模块"
                  value={notificationCenterTab}
                  onChange={(value) => {
                    setNotificationCenterTab(value);
                    if (value === "inbox") {
                      setNotificationPage(1);
                    }
                  }}
                  options={notificationCenterTabOptions}
                  className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
                />
              </div>
            </div>
          ) : null}

          {notificationCenterTab === "inbox" ? (
            <>
              <div className="notification-center-head">
                <div className="system-mgmt-segmented-scroll">
                  <Segmented<NotificationStatusFilter>
                    aria-label="消息筛选"
                    value={notificationStatus}
                    onChange={(value) => {
                      setNotificationStatus(value);
                      setNotificationPage(1);
                    }}
                    options={notificationStatusOptions}
                    className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
                  />
                </div>
                <button type="button" className="sys-btn sys-btn--default sys-btn--sm notification-center-mark-all-btn" onClick={() => void handleMarkAllNotificationsRead()} disabled={unreadCount <= 0}>
                  <CheckCircle2 size={14} />
                  全部已读
                </button>
              </div>

              {notificationLoading ? (
                <div className="workflow-definition-empty-state">
                  <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                  <p>正在加载消息</p>
                </div>
              ) : notifications.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={notificationStatus === "unread" ? "暂无未读消息" : "暂无消息"} />
              ) : (
                <div className="notification-list">
                  {notifications.map((row) => (
                    <article key={row.id} className={`notification-list-item ${row.unread ? "notification-list-item--unread" : ""}`} onClick={() => void handleMarkNotificationRead(row)}>
                      <div className="notification-list-item-head">
                        <div className="min-w-0">
                          <h3>{row.title}</h3>
                          <p>{formatNotificationCategory(row.category)} · {row.publisherName || "系统"} · {formatAccountDate(row.createdAt)}</p>
                        </div>
                        {row.unread ? <span className="notification-unread-dot" aria-label="未读" /> : null}
                      </div>
                      <NotificationContent row={row} />
                    </article>
                  ))}
                </div>
              )}

              {notificationTotal > NOTIFICATION_PAGE_SIZE ? (
                <div className="agent-admin-pagination-wrap mt-4 px-0 py-4">
                  <Pagination
                    className="agent-admin-pagination"
                    current={notificationPage}
                    total={notificationTotal}
                    pageSize={NOTIFICATION_PAGE_SIZE}
                    showSizeChanger={false}
                    onChange={setNotificationPage}
                  />
                </div>
              ) : null}
            </>
          ) : (
            <div className="notification-announcement-editor" aria-label="发布公告">
              <div className="sys-field">
                <label className="sys-field-label sys-field-label--required">标题</label>
                <div className="sys-field-input-wrap">
                  <Bell size={16} className="sys-field-prefix" aria-hidden="true" />
                  <input
                    className="sys-field-input"
                    value={announcementDraft.title}
                    maxLength={160}
                    onChange={(event) => setAnnouncementDraft((draft) => ({ ...draft, title: event.target.value }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <div className="notification-field-label-row">
                  <label className="sys-field-label sys-field-label--required">内容</label>
                  <div className="notification-editor-mode-toggle" role="group" aria-label="内容编辑模式">
                    <button
                      type="button"
                      className={`notification-editor-mode-btn ${announcementEditorMode === "edit" ? "notification-editor-mode-btn--active" : ""}`}
                      onClick={() => setAnnouncementEditorMode("edit")}
                    >
                      <PencilLine size={13} aria-hidden="true" />
                      编辑
                    </button>
                    <button
                      type="button"
                      className={`notification-editor-mode-btn ${announcementEditorMode === "preview" ? "notification-editor-mode-btn--active" : ""}`}
                      onClick={() => setAnnouncementEditorMode("preview")}
                    >
                      <Eye size={13} aria-hidden="true" />
                      预览
                    </button>
                  </div>
                </div>
                {announcementEditorMode === "edit" ? (
                  <textarea
                    className="sys-field-textarea notification-announcement-textarea"
                    value={announcementDraft.contentMarkdown}
                    rows={8}
                    placeholder="支持 Markdown，例如 **重点**、### 标题、列表和链接"
                    onChange={(event) => setAnnouncementDraft((draft) => ({ ...draft, contentMarkdown: event.target.value }))}
                  />
                ) : (
                  <div className="notification-announcement-preview">
                    {announcementDraft.contentMarkdown.trim() ? (
                      <MarkdownRenderer content={announcementDraft.contentMarkdown} />
                    ) : (
                      <p className="notification-plain-content">暂无内容，请切换至编辑模式输入公告正文。</p>
                    )}
                  </div>
                )}
              </div>
              <div className="sys-config-actions notification-announcement-actions">
                <button type="button" className="sys-btn sys-btn--primary" disabled={announcementPublishing} onClick={() => void handlePublishAnnouncement()}>
                  {announcementPublishing ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : <Megaphone size={14} />}
                  发布公告
                </button>
              </div>
            </div>
          )}
        </div>
      </Drawer>
    </main>
  );
}

function NotificationContent({ row }: { row: NotificationRow }) {
  // 公告与定时任务通知均写入 contentMarkdown，统一按 Markdown 渲染。
  return <MarkdownRenderer content={row.contentMarkdown} compact />;
}

function AccountAvatarWithBadge({ avatarUrl, text, unreadCount }: { avatarUrl?: string; text: string; unreadCount: number }) {
  return (
    <span className="account-avatar-badge-wrap">
      <AccountAvatar avatarUrl={avatarUrl} text={text} />
      {unreadCount > 0 ? <span className="account-avatar-unread-badge">{formatUnreadCount(unreadCount)}</span> : null}
    </span>
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

function formatUnreadCount(count: number): string {
  return count > 99 ? "99+" : String(count);
}

function formatNotificationCategory(category: string): string {
  if (category === "schedule_result") return "定时任务";
  if (category === "system_notice") return "系统通知";
  return category;
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
