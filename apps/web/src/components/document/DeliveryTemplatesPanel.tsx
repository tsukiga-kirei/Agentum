import { downloadTemplateFile } from "../../utils/deliveryTemplateFile";
import type { CSSProperties } from "react";
import { useCallback, useEffect, useState } from "react";
import { App, Drawer, Empty, Pagination, ConfigProvider, Spin } from "antd";
import { Copy, Download, Eye, FileSpreadsheet, FileText, Plus, Save, Search, Send, Upload, Trash2, History, CircleAlert } from "lucide-react";
import { assetApi, deliveryTemplateApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import type {
  DeliveryTemplate,
  TemplateStyle,
  TemplateWorkbookSheet,
  TemplateSpec,
} from "../../types/delivery-template";
import { inferTemplateExcelRegions, sampleTemplateValue } from "../../types/delivery-template";
import {
  TemplateFieldsEditor,
  TemplateFieldLocations,
  TemplateTabs,
  TemplateFilePicker,
  TemplateInput,
  TemplateSelect,
  TemplateStyleEditor,
  TemplateValuesEditor,
} from "./TemplateEditors";
import { SysModalMask } from "../common/SysModalMask";
import { TemplateFormatGroupsEditor } from "./TemplateFormatGroupsEditor";
import { applyTemplateFieldPreset, reconcileTemplateFormatGroups, synchronizeTemplateTrialValues } from "../../utils/templateFormatGroups";
import { WordDocumentPreviewDrawer } from "../runtime/WordDocumentPreviewDrawer";
import { ExcelWorkbookPreviewDrawer } from "../runtime/ExcelWorkbookPreviewDrawer";

export function TemplatePreview({
  file,
  kind,
  onClose,
}: {
  file: { blob: Blob; fileName: string; layout?: TemplateWorkbookSheet[] };
  kind: "word" | "excel";
  onClose: () => void;
}) {
  const [sheetIndex, setSheetIndex] = useState(0);
  const [zoom, setZoom] = useState(100);
  const columnName = (index: number): string => index < 26 ? String.fromCharCode(65 + index) : columnName(Math.floor(index / 26) - 1) + String.fromCharCode(65 + index % 26);
  const theme = useAuthStore((state) => state.themeMode);
  const Preview = kind === "word" ? WordDocumentPreviewDrawer : ExcelWorkbookPreviewDrawer;
  const load = useCallback(async () => ({ blob: file.blob }), [file.blob]);
  if (file.layout) {
    const sheet = file.layout[sheetIndex] ?? file.layout[0];
    return (
      <Drawer
        open
        title={file.fileName}
        size="min(1200px, 96vw)"
        rootClassName={getThemedDrawerRootClassName(theme, "template-preview-drawer")}
        onClose={onClose}
        extra={
          <button
            type="button"
            className="sys-btn sys-btn--default"
            onClick={() => downloadTemplateFile(file)}
          >
            <Download size={15} />
            下载文件
          </button>
        }
      >
        <div className="sys-drawer-section template-preview-content"><div className="template-preview-toolbar"><p className="sys-field-hint">
          模板样式预览展示底色、字体、边框、合并、列宽和行高。公式已在完整数据写入后计算；打印分页请下载检查。
        </p><TemplateSelect value={zoom} options={[75, 100, 125, 150].map(value => ({ value, label: `${value}%` }))} onChange={setZoom} /></div>
        <TemplateTabs
          value={sheetIndex}
          options={file.layout.map((item, index) => ({ value: index, label: item.name }))}
          onChange={(next) => setSheetIndex(Number(next))}
        />
        {sheet?.truncated && (
          <p className="sys-field-hint">仅预览前 200 行、40 列；下载文件包含完整数据。</p>
        )}
        <div className="template-sheet-preview" tabIndex={0} aria-label={`${sheet?.name} 工作表预览`}><div style={{ zoom: zoom / 100 }}>
          <table>
            <colgroup><col style={{ width: 44 }} />
              {sheet?.widths.map((width, index) => (
                <col key={index} style={{ width }} />
              ))}
            </colgroup>
            <thead><tr><th aria-label="行号" />{sheet?.widths.map((_, index) => <th key={index}>{columnName(index)}</th>)}</tr></thead><tbody>
              {sheet?.rows.map((row, index) => (
                <tr key={index} style={{ height: `${row.height}pt` }}><th scope="row">{index + 1}</th>
                  {row.cells.map((cell) => (
                    <td
                      key={cell.column}
                      rowSpan={cell.rowSpan}
                      colSpan={cell.colSpan}
                      style={cell.style as CSSProperties}
                      title={cell.formula ? `=${cell.formula}` : undefined}
                    >
                      {cell.text}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div></div></div>
      </Drawer>
    );
  }
  return (
    <Preview
      open
      fileName={file.fileName}
      loadDocument={load}
      downloading={false}
      onClose={onClose}
      onDownload={() => downloadTemplateFile(file)}
    />
  );
}

export function DeliveryTemplatesPanel({
  onPublished,
}: {
  onPublished?: (template: DeliveryTemplate) => void;
}) {
  const { message } = App.useApp();
  const token = useAuthStore((state) => state.token);
  const user = useAuthStore((state) => state.user);
  const activeRole = useAuthStore((state) => state.activeRole);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const theme = useAuthStore((state) => state.themeMode);
  const [items, setItems] = useState<DeliveryTemplate[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [name, setName] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DeliveryTemplate | null>(null);
  const [history, setHistory] = useState<DeliveryTemplate[]>([]);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [editing, setEditing] = useState<DeliveryTemplate | null>(null);
  const [tab, setTab] = useState("fields");
  const [styleTarget, setStyleTarget] = useState("default");
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [capabilities, setCapabilities] = useState<
    { value: string; label: string; kind: string }[]
  >([]);
  const [capabilityId, setCapabilityId] = useState("");
  const [preview, setPreview] = useState<{
    blob: Blob;
    fileName: string;
    kind: "word" | "excel";
    layout?: TemplateWorkbookSheet[];
  } | null>(null);
  const load = useCallback(async () => {
    if (!token || !tenantId) return;
    setBusy(true);
    try {
      const result = await deliveryTemplateApi.list(tenantId, token, page, false, search);
      setItems(result.items);
      setTotal(result.total);
    } catch (err) {
      void message.error(err instanceof Error ? err.message : "模板列表加载失败", 6);
    } finally {
      setBusy(false);
    }
  }, [message, token, tenantId, page, search]);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (!token || !tenantId || !editing) return;
    let disposed = false;
    void assetApi
      .listSystemCapabilities(tenantId, token, 1, 100, "openedAt,desc", "delivery")
      .then((result) => {
        if (disposed) return;
        setCapabilities(
          result.items
            .filter((item) => ["word", "excel"].includes(String(item.config.documentKind)))
            .map((item) => ({
              value: item.id,
              label: item.name,
              kind: String(item.config.documentKind),
            })),
        );
      })
      .catch((err) => {
        if (!disposed) void message.error(err instanceof Error ? err.message : "交付能力加载失败", 6);
      });
    return () => {
      disposed = true;
    };
  }, [message, token, tenantId, editing?.id]);
  const action = async (work: () => Promise<void>) => {
    setBusy(true);
    try {
      await work();
    } catch (err) {
      // 上传、保存及试填可能在多层抽屉中失败，统一在顶部反馈，避免错误落到遮罩后的列表。
      void message.error(err instanceof Error ? err.message : "模板操作失败", 6);
    } finally {
      setBusy(false);
    }
  };
  const open = (template: DeliveryTemplate) => {
    setEditing({ ...structuredClone(template), spec: template.kind === "excel" ? { ...structuredClone(template.spec), excelRegions: inferTemplateExcelRegions(template.spec) } : structuredClone(template.spec) });
    setTab("fields");
    setHistoryOpen(false);
    setHistoryPage(1);
    setStyleTarget("default");
    setCapabilityId("");
    setValues(
      Object.fromEntries(
        template.spec.fields.map((field) => [field.key, sampleTemplateValue(field)]),
      ),
    );
  };
  const editable = Boolean(editing && !editing.deleted && editing.createdBy === user?.id);
  useEffect(() => {
    if (!historyOpen || !editing?.id || !token || !tenantId) return;
    let disposed = false;
    void deliveryTemplateApi.versions(tenantId, token, editing.id, historyPage).then(result => {
      if (!disposed) { setHistory(result.items); setHistoryTotal(result.total); }
    }).catch(error => { if (!disposed) void message.error(error instanceof Error ? error.message : "版本记录加载失败", 6); });
    return () => { disposed = true; };
  }, [historyOpen, historyPage, editing?.id, tenantId, token, message]);
  const styleValue = (): TemplateStyle => {
    if (!editing) return {};
    if (styleTarget === "default") return editing.spec.defaultStyle ?? {};
    if (styleTarget.startsWith("sheet:"))
      return editing.spec.sheetStyles?.[styleTarget.slice(6)] ?? {};
    return editing.spec.locations.find((location) => location.id === styleTarget)?.style ?? {};
  };
  const updateStyle = (style: TemplateStyle) => {
    if (!editing || !editable) return;
    const spec = { ...editing.spec };
    if (styleTarget === "default") spec.defaultStyle = style;
    else if (styleTarget.startsWith("sheet:"))
      spec.sheetStyles = { ...spec.sheetStyles, [styleTarget.slice(6)]: style };
    else
      spec.locations = spec.locations.map((location) =>
        location.id === styleTarget ? { ...location, style } : location,
      );
    setEditing({ ...editing, spec });
  };
  const updateTemplateSpec = (spec: TemplateSpec) => {
    if (!editing || !editable) return;
    setValues(current => synchronizeTemplateTrialValues(editing.spec.fields, spec.fields, current));
    setEditing({ ...editing, spec });
  };
  return (
    <div className="template-library">
      <div className="template-toolbar">
        <div className="sys-field-input-wrap">
          <Search className="sys-field-prefix" size={16} />
          <input
            aria-label="搜索交付模板"
            className="sys-field-input"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                setPage(1);
                setSearch(keyword);
              }
            }}
            placeholder="搜索模板名称"
          />
        </div>
        <button
          className="sys-btn sys-btn--default"
          onClick={() => {
            setPage(1);
            setSearch(keyword);
          }}
        >
          搜索
        </button>
        <button
          className="sys-btn sys-btn--primary"
          onClick={() => {
            setName("");
            setFile(null);
            setUploading(true);
          }}
        >
          <Plus size={16} />
          上传模板
        </button>
      </div>
      <Spin spinning={busy}>
        <div className="sys-card-grid">
          {items.map((item) => (
            <article key={item.id} className="sys-card template-library-card" tabIndex={0} role="button" aria-label={`${item.createdBy === user?.id ? "配置" : "查看"}模板：${item.name}`}
              onClick={() => open(item)} onKeyDown={(event) => { if (event.target === event.currentTarget && ["Enter", " "].includes(event.key)) { event.preventDefault(); open(item); } }}>
              <div className="sys-card-header">
                <span className="sys-card-avatar sys-card-avatar--cap">{item.kind === "excel" ? <FileSpreadsheet size={22} /> : <FileText size={22} />}</span>
                <div className="sys-card-info"><h3 className="sys-card-name">{item.name}</h3><p className="sys-card-code">{item.kind === "word" ? "Word 文档" : "Excel 工作簿"} · v{item.version}</p></div>
                <span
                  className={`sys-info-tag ${item.status === "published" ? "sys-info-tag--success" : "sys-info-tag--warn"}`}
                >
                  {item.status === "published" ? "已发布" : "草稿"}
                </span>
              </div>
              <p className="template-card-meta">
                {item.spec.fields.length} 个字段 · {new Set(item.spec.locations.map(location => location.sheet).filter(Boolean)).size || 1} {item.kind === "excel" ? "个 Sheet" : "份文档"} ·{" "}
                {item.readScope === "all" ? "租户内共享" : "仅自己"}
              </p>
              <div className="template-card-actions" onClick={(event) => event.stopPropagation()}>
                <button className="sys-btn sys-btn--default" onClick={() => open(item)}>
                  <Eye size={15} />
                  {item.createdBy === user?.id ? "配置" : "查看"}
                </button>
                <button
                  className="sys-btn sys-btn--default"
                  disabled={busy}
                  onClick={() =>
                    void action(async () => {
                      if (token && tenantId) {
                        const copy = await deliveryTemplateApi.copy(tenantId, token, item.id);
                        open(copy);
                        await load();
                      }
                    })
                  }
                >
                  <Copy size={15} />
                  复制
                </button>
                {item.createdBy === user?.id && <button type="button" className="sys-btn sys-btn--danger" disabled={busy} onClick={() => setDeleteTarget(item)}><Trash2 size={15} />删除</button>}
                {onPublished && item.status === "published" && (
                  <button className="sys-btn sys-btn--primary" onClick={() => onPublished(item)}>
                    使用模板
                  </button>
                )}
              </div>
            </article>
          ))}
        </div>
        {items.length === 0 && (
          <Empty description="暂无交付模板，上传已设置填写标记的 Word 或 Excel 文件" />
        )}
      </Spin>
      <div className="mt-5 flex justify-end">
        <Pagination
          current={page}
          pageSize={10}
          total={total}
          showSizeChanger={false}
          onChange={setPage}
        />
      </div>
      <Drawer
        open={uploading}
        title="上传交付模板"
        size={560}
        rootClassName={getThemedDrawerRootClassName(theme, "template-config-drawer")}
        onClose={() => setUploading(false)}
        destroyOnHidden
      >
        <div className="sys-drawer-section grid gap-5">
          <TemplateInput label="模板名称" value={name} onChange={setName} />
          <TemplateFilePicker
            key={uploading ? "upload-open" : "upload-closed"}
            label="选择模板文件"
            accept=".docx,.xlsx"
            fileName={file?.name}
            disabled={busy}
            onChange={(next) => {
              setFile(next);
              if (!name) setName(next.name.replace(/\.(docx|xlsx)$/i, ""));
            }}
          />
          <p className="sys-field-hint">
            请先在 Word 正文、表格或 Excel 单元格中标出需要自动填写的位置，例如：公司名称：
            {"{{company_name}}"}。字段标识以小写英文字母开头，仅包含小写字母、数字和下划线。
          </p>
          <p className="sys-field-hint">
            在原文件中使用 {"{{risk_summary}}"} 标记文字位置；重复表格样板行使用{" "}
            {"{{risk_items[].category}}"}、{"{{risk_items[].level}}"}{" "}
            等标记。Excel 并列明细会自动识别为独立区域；Word 同一样板行使用同一个列表。
          </p>
        </div>
        <div className="sys-drawer-footer">
          <div className="sys-drawer-footer-right">
            <button className="sys-btn sys-btn--default" onClick={() => setUploading(false)}>
              取消
            </button>
            <button
              disabled={busy || !file || !name.trim()}
              className="sys-btn sys-btn--primary"
              onClick={() =>
                void action(async () => {
                  if (!token || !tenantId || !file) return;
                  const template = await deliveryTemplateApi.upload(tenantId, token, name, file);
                  setUploading(false);
                  open(template);
                  await load();
                })
              }
            >
              <Upload size={15} />
              上传并配置
            </button>
          </div>
        </div>
      </Drawer>
      <Drawer
        open={Boolean(editing)}
        title={
          editing
            ? `${editing.name} · v${editing.version} · ${editing.status === "published" ? "已发布" : "草稿"}`
            : "配置模板"
        }
        size={1000}
        rootClassName={getThemedDrawerRootClassName(theme, "template-config-drawer")}
        onClose={() => setEditing(null)}
        destroyOnHidden
      >
        {editing && (
          <div className="sys-drawer-section template-editor-scroll">
            <div className="template-toolbar">
              <button type="button" className="sys-btn sys-btn--default" onClick={() => setHistoryOpen(!historyOpen)}><History size={15} />版本记录</button>
              {editable && <button type="button" className="sys-btn sys-btn--danger" disabled={busy} onClick={() => setDeleteTarget(editing)}><Trash2 size={15} />删除模板</button>}
              <button
                className="sys-btn sys-btn--default"
                onClick={() =>
                  void action(async () => {
                    if (token && tenantId)
                      downloadTemplateFile(
                        await deliveryTemplateApi.file(tenantId, token, editing.id),
                      );
                  })
                }
              >
                <Download size={15} />
                下载原模板
              </button>
            </div>
            <TemplateTabs
              value={tab}
              options={[
                { value: "fields", label: "字段设置" },
                { value: "style", label: "格式配置" },
                { value: "preview", label: "试填与检查" },
              ]}
              onChange={(value) => setTab(String(value))}
            />
            {historyOpen && <section className="template-field-card grid gap-3"><strong>版本记录</strong><p className="sys-field-hint">流程固定引用所选发布版本。修改已发布内容后保存为新草稿；发布新版本后，可在流程中明确切换。</p>{history.map(version => <div className="template-section-heading" key={version.id}><span>v{version.version} · {version.status === "published" ? "已发布" : "草稿"} · {new Date(version.updatedAt).toLocaleString()}{version.deleted ? " · 已删除" : ""}</span><button type="button" className="sys-btn sys-btn--default sys-btn--sm" disabled={busy || version.id === editing.id} onClick={() => open(version)}>打开</button></div>)}<Pagination size="small" current={historyPage} total={historyTotal} pageSize={10} showSizeChanger={false} onChange={setHistoryPage} /></section>}
            {editable && editing.status === "published" && <p className="sys-field-hint">当前为 v{editing.version} 发布内容，可直接编辑。保存修改会创建本模板的新版本草稿，已有流程继续使用原版本。</p>}
            {!editable && <div className="template-readonly-notice"><div><strong>{editing.deleted ? "此模板已从库中删除" : "共享模板，当前仅可查看"}</strong><p>可复制为独立模板后修改名称、字段和格式。</p></div><button type="button" disabled={busy} className="sys-btn sys-btn--default" onClick={() => void action(async () => { if (token && tenantId) { open(await deliveryTemplateApi.copy(tenantId, token, editing.id)); await load(); } })}><Copy size={15} />复制模板</button></div>}
            {tab === "fields" && (
              <ConfigProvider componentDisabled={!editable}><fieldset disabled={!editable} className="template-editor-fieldset grid gap-5">
                <TemplateInput
                  label="模板名称"
                  value={editing.name}
                  onChange={(name) => setEditing({ ...editing, name })}
                />
                <div className="sys-field">
                  <label className="sys-field-label">发布后可见范围</label>
                  <TemplateSelect
                    disabled={!editable}
                    value={editing.readScope}
                    options={[
                      { value: "self", label: "仅自己" },
                      { value: "all", label: "租户内共享（已发布版本）" },
                    ]}
                    onChange={(readScope) =>
                      setEditing({ ...editing, readScope: readScope as "self" | "all" })
                    }
                  />
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">
                    替换模板原件（重新识别字段和格式，已发布内容另存新版本）
                  </label>
                  <TemplateFilePicker
                    key={editing.id}
                    label="选择替换模板文件"
                    accept={editing.kind === "word" ? ".docx" : ".xlsx"}
                    fileName={editing.fileName}
                    disabled={busy || !editable}
                    onChange={(replacement) => {
                      if (token && tenantId)
                        void action(async () => {
                          open(
                            await deliveryTemplateApi.replaceFile(
                              tenantId,
                              token,
                              editing.id,
                              replacement,
                            ),
                          );
                          await load();
                        });
                    }}
                  />
                </div>
                {editing.kind === "excel" && <details className="template-region-settings"><summary><strong>Excel 公式与动态范围说明</strong></summary><div className="grid gap-3"><p className="sys-field-hint">汇总页可直接写 Excel 公式，不需要重复绑定汇总参数。金额字段请选择“数字”，避免数字字符串被 SUM 忽略。</p><p className="sys-field-hint">跳过前两行可写 =SUM('信贷业务'!D3:D1048576)；若列内有小计或其他金额，请使用 Excel 表格列名，或下方各填写位置的“公式引用名称”。</p><p className="sys-field-hint">推移模式会为实际记录留出空间，后面的表头、合计和说明整体移动；固定模式遇到覆盖会报错。Excel 表格的数据范围、计算列和关联命名范围会更新。普通样板字段旁连续的公式视为计算列；固定汇总请放在数据区外并用空列隔开。全部 Sheet 写完后统一计算。循环引用、错误引用和无法计算的函数会明确报出位置。</p></div></details>}
                <TemplateFieldsEditor
                  lockedKeys
                  locations={editing.spec.locations}
                  formatGroups={editing.spec.formatGroups}
                  onApplyPreset={(paths, preset) => updateTemplateSpec(applyTemplateFieldPreset(editing.spec, paths, preset))}
                  renderLocations={editing.kind === "excel" ? (field, parent) => <TemplateFieldLocations field={parent ?? field} column={parent ? field.key : undefined} spec={editing.spec} onChange={spec => setEditing({ ...editing, spec })} /> : undefined}
                  fields={editing.spec.fields}
                  onChange={(fields) =>
                    updateTemplateSpec(reconcileTemplateFormatGroups(editing.spec, fields))
                  }
                />
              </fieldset></ConfigProvider>
            )}
            {tab === "style" && (
              <div className="grid gap-5">
                <ConfigProvider componentDisabled={!editable}><TemplateFormatGroupsEditor spec={editing.spec} kind={editing.kind} disabled={!editable} onChange={updateTemplateSpec} /></ConfigProvider>
                <details className="template-region-settings"><summary><strong>高级设置：全局默认、Sheet 与单独位置</strong></summary><p className="sys-field-hint">优先级：原件 → 全局默认 → Sheet 默认 → 字段方案 → 单独位置 → 流程覆盖。通常只需配置上方方案；行高、列宽仍影响整行、整列。</p>
                <TemplateSelect
                  value={styleTarget}
                  options={[
                    { value: "default", label: "全部填充内容的默认格式" },
                    ...[
                      ...new Set(
                        editing.spec.locations.map((location) => location.sheet).filter(Boolean),
                      ),
                    ].map((sheet) => ({
                      value: `sheet:${sheet}`,
                      label: `Sheet「${sheet}」默认格式`,
                    })),
                    ...editing.spec.locations.map((location) => ({
                      value: location.id,
                      label: location.label,
                    })),
                  ]}
                  onChange={setStyleTarget}
                />
                <ConfigProvider componentDisabled={!editable}><fieldset disabled={!editable} className="template-editor-fieldset">
                  <TemplateStyleEditor
                    kind={editing.kind}
                    value={styleValue()}
                    onChange={updateStyle}
                  />
                </fieldset></ConfigProvider>
                </details>
              </div>
            )}
            {tab === "preview" && (
              <div className="template-test-panel">
                <div className="template-test-intro"><div><strong>填写试验数据</strong><p className="sys-field-hint">仅用于检查字段、展开效果和样式，不运行 AI、不产生正式交付记录。生成前自动保存草稿；打印和公式效果请下载检查。</p></div><span className="sys-info-tag">{editing.spec.fields.length} 个字段</span></div>
                <div className="sys-field"><label className="sys-field-label">试填使用的交付能力</label>
                <TemplateSelect
                  value={capabilityId || undefined}
                  options={capabilities.filter((capability) => capability.kind === editing.kind)}
                  onChange={setCapabilityId}
                  placeholder={editing.kind === "excel" ? "选择 Excel 交付能力" : "选择 Word 交付能力"}
                />
                </div>
                <TemplateValuesEditor fields={editing.spec.fields} values={values} onChange={setValues} />
                <div className="template-test-actions">
                <button
                  disabled={busy || !capabilityId}
                  className="sys-btn sys-btn--primary"
                  onClick={() =>
                    void action(async () => {
                      if (token && tenantId) {
                        const saved = editable ? await deliveryTemplateApi.save(tenantId, token, editing) : editing;
                        setEditing(saved);
                        if (saved.id !== editing.id) await load();
                        const file = await deliveryTemplateApi.preview(
                          tenantId,
                          token,
                          saved.id,
                          { capabilityId, values, fileName: `${editing.name}-试填` },
                        );
                        setPreview({
                          ...file,
                          kind: editing.kind,
                          layout:
                            editing.kind === "excel"
                              ? await deliveryTemplateApi.previewLayout(
                                  tenantId,
                                  token,
                                  saved.id,
                                  { capabilityId, values, fileName: `${editing.name}-试填` },
                                )
                              : undefined,
                        });
                      }
                    })
                  }
                >
                  <Eye size={15} />
                  生成试填预览
                </button>
                </div>
              </div>
            )}
          </div>
        )}
        <div className="sys-drawer-footer">
          <div className="sys-drawer-footer-right">
            <button className="sys-btn sys-btn--default" onClick={() => setEditing(null)}>
              关闭
            </button>
            {editable && (
              <>
                <button
                  className="sys-btn sys-btn--default"
                  disabled={busy}
                  onClick={() =>
                    void action(async () => {
                      if (token && tenantId && editing) {
                        setEditing(await deliveryTemplateApi.save(tenantId, token, editing));
                        await load();
                      }
                    })
                  }
                >
                  <Save size={15} />
                  {editing?.status === "published" ? "保存修改" : "保存草稿"}
                </button>
                <button
                  className="sys-btn sys-btn--primary"
                  disabled={busy}
                  onClick={() =>
                    void action(async () => {
                      if (token && tenantId && editing) {
                        const saved = await deliveryTemplateApi.save(tenantId, token, editing);
                        setEditing(saved);
                        await load();
                        const result = await deliveryTemplateApi.publish(
                          tenantId,
                          token,
                          saved.id,
                        );
                        setEditing(result);
                        await load();
                        onPublished?.(result);
                      }
                    })
                  }
                >
                  <Send size={15} />
                  保存并发布
                </button>
              </>
            )}
          </div>
        </div>
      </Drawer>
      {deleteTarget && <SysModalMask className="agent-delete-confirm-mask" onClose={() => !busy && setDeleteTarget(null)}><div className="sys-modal agent-delete-confirm-modal" role="dialog" aria-modal="true" aria-labelledby="delete-delivery-template-title"><div className="agent-delete-confirm-body"><div className="agent-delete-confirm-icon"><CircleAlert size={28} /></div><div className="agent-delete-confirm-content"><h2 id="delete-delivery-template-title">删除模板</h2><p>确认删除“{deleteTarget.name}”及其版本记录？模板将从库中移除；已有流程固定引用的版本保留，仍可继续交付和查看。</p></div></div><div className="agent-delete-confirm-footer"><button type="button" className="sys-btn sys-btn--default" disabled={busy} onClick={() => setDeleteTarget(null)}>取消</button><button type="button" className="sys-btn sys-btn--danger" disabled={busy} onClick={() => void action(async () => { if (!token || !tenantId) return; await deliveryTemplateApi.delete(tenantId, token, deleteTarget.id); setDeleteTarget(null); setEditing(null); void message.success("模板已删除"); if (page > 1 && items.length === 1) setPage(page - 1); else await load(); })}>删除</button></div></div></SysModalMask>}
      {preview && (
        <TemplatePreview file={preview} kind={preview.kind} onClose={() => setPreview(null)} />
      )}
    </div>
  );
}
