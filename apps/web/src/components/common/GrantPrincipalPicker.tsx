import { useEffect, useMemo, useState } from "react";
import { Pagination } from "antd";
import {
  Building2,
  Check,
  Search,
  ShieldCheck,
  UserRound,
  UserRoundCog,
  UsersRound,
  X,
} from "lucide-react";
import type {
  OrganizationDepartment,
  OrganizationMember,
  OrganizationRole,
  PrincipalType,
  TenantOrganizationOverview,
} from "../../types/organization";
import { SysModalMask } from "./SysModalMask";

type PrincipalSelectionKey = `${PrincipalType}:${string}`;
type PrincipalPickerTab = {
  key: PrincipalType;
  label: string;
  icon: typeof ShieldCheck;
};
type PrincipalPickerOption = {
  key: PrincipalSelectionKey;
  label: string;
  description: string;
  status: string;
};

const PRINCIPAL_PAGE_SIZE = 8;
const principalTabs: PrincipalPickerTab[] = [
  { key: "role", label: "角色", icon: ShieldCheck },
  { key: "department", label: "部门", icon: Building2 },
  { key: "user", label: "人员", icon: UserRound },
];

const paginationLocale = {
  page: "页",
  prev_page: "上一页",
  next_page: "下一页",
  prev_5: "向前 5 页",
  next_5: "向后 5 页",
};

type GrantPrincipalPickerProps = {
  value: PrincipalSelectionKey[];
  overview: TenantOrganizationOverview | null;
  onChange: (value: PrincipalSelectionKey[]) => void;
};

/**
 * 分配对象独立选择器：将角色、部门、人员拆成清晰维度，并对长人员列表做搜索与分页。
 * 弹窗内先维护临时选择，只有点击“确认选择”才写回抽屉表单，避免误触立即改变权限。
 */
export function GrantPrincipalPicker({ value, overview, onChange }: GrantPrincipalPickerProps) {
  const [open, setOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<PrincipalType>("role");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);
  const [draftValue, setDraftValue] = useState<PrincipalSelectionKey[]>(value);

  const optionsByType = useMemo(
    () => ({
      role: buildRoleOptions(overview?.roles ?? []),
      department: buildDepartmentOptions(overview?.departments ?? []),
      user: buildUserOptions(overview?.members ?? []),
    }),
    [overview],
  );

  const filteredOptions = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLocaleLowerCase("zh-CN");
    if (!normalizedKeyword) return optionsByType[activeTab];
    return optionsByType[activeTab].filter((option) =>
      `${option.label} ${option.description}`.toLocaleLowerCase("zh-CN").includes(normalizedKeyword),
    );
  }, [activeTab, keyword, optionsByType]);

  const visibleOptions = useMemo(() => {
    const start = (page - 1) * PRINCIPAL_PAGE_SIZE;
    return filteredOptions.slice(start, start + PRINCIPAL_PAGE_SIZE);
  }, [filteredOptions, page]);

  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(filteredOptions.length / PRINCIPAL_PAGE_SIZE));
    if (page > maxPage) setPage(maxPage);
  }, [filteredOptions.length, page]);

  const selectedCountByType = useMemo(
    () => ({
      role: draftValue.filter((key) => key.startsWith("role:")).length,
      department: draftValue.filter((key) => key.startsWith("department:")).length,
      user: draftValue.filter((key) => key.startsWith("user:")).length,
    }),
    [draftValue],
  );

  const committedCountByType = useMemo(
    () => ({
      role: value.filter((key) => key.startsWith("role:")).length,
      department: value.filter((key) => key.startsWith("department:")).length,
      user: value.filter((key) => key.startsWith("user:")).length,
    }),
    [value],
  );

  const currentPageAllSelected = visibleOptions.length > 0 && visibleOptions.every((option) => draftValue.includes(option.key));

  function handleOpen() {
    setDraftValue(value);
    setActiveTab("role");
    setKeyword("");
    setPage(1);
    setOpen(true);
  }

  function handleClose() {
    setDraftValue(value);
    setOpen(false);
  }

  function handleTabChange(nextTab: PrincipalType) {
    setActiveTab(nextTab);
    setKeyword("");
    setPage(1);
  }

  function toggleOption(optionKey: PrincipalSelectionKey) {
    setDraftValue((keys) => keys.includes(optionKey) ? keys.filter((key) => key !== optionKey) : [...keys, optionKey]);
  }

  function toggleCurrentPage() {
    const visibleKeys = visibleOptions.map((option) => option.key);
    setDraftValue((keys) => currentPageAllSelected
      ? keys.filter((key) => !visibleKeys.includes(key))
      : [...keys, ...visibleKeys.filter((key) => !keys.includes(key))]);
  }

  function handleConfirm() {
    onChange(draftValue);
    setOpen(false);
  }

  return (
    <>
      <button type="button" className="tenant-principal-picker-trigger" onClick={handleOpen}>
        <span className="tenant-principal-picker-trigger-icon"><UserRoundCog size={17} aria-hidden="true" /></span>
        {value.length === 0 ? (
          <span className="tenant-principal-picker-trigger-placeholder">选择角色、部门或人员</span>
        ) : (
          <span className="tenant-principal-picker-trigger-value">
            <strong>已选择 {value.length} 个对象</strong>
            <small>
              角色 {committedCountByType.role} · 部门 {committedCountByType.department} · 人员 {committedCountByType.user}
            </small>
          </span>
        )}
        <span className="tenant-principal-picker-trigger-action">选择</span>
      </button>

      {open ? (
        <SysModalMask onClose={handleClose} className="sys-modal-mask--over-drawer">
          <div className="sys-modal tenant-principal-picker-modal" role="dialog" aria-modal="true" aria-labelledby="tenant-principal-picker-title">
            <div className="sys-modal-header tenant-principal-picker-header">
              <div>
                <h2 id="tenant-principal-picker-title" className="sys-modal-title">选择分配对象</h2>
                <p>按角色、部门或人员分别选择，已选 {draftValue.length} 个对象。</p>
              </div>
              <button type="button" className="sys-modal-close" aria-label="关闭" onClick={handleClose}><X size={18} /></button>
            </div>

            <div className="sys-modal-body tenant-principal-picker-body">
              <div className="tenant-principal-picker-tabs" role="tablist" aria-label="分配对象类型">
                {principalTabs.map((tab) => {
                  const Icon = tab.icon;
                  const active = tab.key === activeTab;
                  return (
                    <button
                      key={tab.key}
                      type="button"
                      role="tab"
                      aria-selected={active}
                      className={`tenant-principal-picker-tab ${active ? "tenant-principal-picker-tab--active" : ""}`}
                      onClick={() => handleTabChange(tab.key)}
                    >
                      <Icon size={16} aria-hidden="true" />
                      <span>{tab.label}</span>
                      <span className="tenant-principal-picker-tab-count">{selectedCountByType[tab.key]}</span>
                    </button>
                  );
                })}
              </div>

              <div className="tenant-principal-picker-toolbar">
                <label className="tenant-principal-picker-search">
                  <Search size={16} aria-hidden="true" />
                  <input
                    value={keyword}
                    placeholder={`搜索${principalTabs.find((tab) => tab.key === activeTab)?.label ?? "对象"}`}
                    aria-label={`搜索${principalTabs.find((tab) => tab.key === activeTab)?.label ?? "对象"}`}
                    onChange={(event) => {
                      setKeyword(event.target.value);
                      setPage(1);
                    }}
                  />
                  {keyword ? <button type="button" aria-label="清空搜索" onClick={() => setKeyword("")}><X size={14} /></button> : null}
                </label>
                <button type="button" className="tenant-principal-picker-page-action" disabled={visibleOptions.length === 0} onClick={toggleCurrentPage}>
                  {currentPageAllSelected ? "取消本页" : "选择本页"}
                </button>
              </div>

              <div className="tenant-principal-picker-list">
                {visibleOptions.length === 0 ? (
                  <div className="tenant-principal-picker-empty">
                    <UsersRound size={28} aria-hidden="true" />
                    <span>{keyword ? "未找到匹配对象" : "当前没有可选对象"}</span>
                  </div>
                ) : visibleOptions.map((option) => {
                  const selected = draftValue.includes(option.key);
                  return (
                    <button
                      key={option.key}
                      type="button"
                      className={`tenant-principal-picker-option ${selected ? "tenant-principal-picker-option--selected" : ""}`}
                      aria-pressed={selected}
                      onClick={() => toggleOption(option.key)}
                    >
                      <span className="tenant-principal-picker-option-check">{selected ? <Check size={14} aria-hidden="true" /> : null}</span>
                      <span className="tenant-principal-picker-option-text">
                        <strong>{option.label}</strong>
                        <small>{option.description}</small>
                      </span>
                      {option.status !== "active" ? <span className="tenant-principal-picker-status">已停用</span> : null}
                    </button>
                  );
                })}
              </div>

              <div className="tenant-principal-picker-pagination">
                <span>共 {filteredOptions.length} 项，每页 {PRINCIPAL_PAGE_SIZE} 项</span>
                <Pagination
                  current={page}
                  pageSize={PRINCIPAL_PAGE_SIZE}
                  total={filteredOptions.length}
                  showSizeChanger={false}
                  hideOnSinglePage
                  locale={paginationLocale}
                  onChange={setPage}
                />
              </div>
            </div>

            <div className="sys-modal-footer tenant-principal-picker-footer">
              <button type="button" className="tenant-principal-picker-clear" disabled={draftValue.length === 0} onClick={() => setDraftValue([])}>
                清空已选
              </button>
              <div>
                <button type="button" className="sys-btn sys-btn--default" onClick={handleClose}>取消</button>
                <button type="button" className="sys-btn sys-btn--primary" onClick={handleConfirm}>确认选择（{draftValue.length}）</button>
              </div>
            </div>
          </div>
        </SysModalMask>
      ) : null}
    </>
  );
}

function buildRoleOptions(roles: OrganizationRole[]): PrincipalPickerOption[] {
  return roles.map((role) => ({
    key: `role:${role.id}`,
    label: role.name,
    description: role.description || `角色编码：${role.code}`,
    status: role.status,
  }));
}

function buildDepartmentOptions(departments: OrganizationDepartment[]): PrincipalPickerOption[] {
  return departments.map((department) => ({
    key: `department:${department.id}`,
    label: department.name,
    description: `部门编码：${department.code}`,
    status: department.status,
  }));
}

function buildUserOptions(members: OrganizationMember[]): PrincipalPickerOption[] {
  return members.map((member) => ({
    key: `user:${member.id}`,
    label: member.displayName || member.username,
    description: [member.username, member.email].filter(Boolean).join(" · "),
    status: member.status,
  }));
}
