import { downloadTemplateFile } from "../../utils/deliveryTemplateFile";
import type { CSSProperties } from "react";
import { useCallback, useEffect, useState } from "react";
import { App, Drawer, Empty, Pagination, Segmented, Spin } from "antd";
import { Copy, Download, Eye, FileSpreadsheet, FileText, Plus, Save, Search, Send, Upload } from "lucide-react";
import { assetApi, deliveryTemplateApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import type {
  DeliveryTemplate,
  TemplateStyle,
  TemplateWorkbookSheet,
} from "../../types/delivery-template";
import { inferTemplateExcelRegions, sampleTemplateValue } from "../../types/delivery-template";
import {
  TemplateFieldsEditor,
  TemplateFilePicker,
  TemplateInput,
  TemplateSelect,
  TemplateStyleEditor,
  TemplateValuesEditor,
} from "./TemplateEditors";
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
        rootClassName={getThemedDrawerRootClassName(theme)}
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
        <p className="sys-field-hint mb-4">
          模板样式预览展示底色、字体、边框、合并、列宽和行高。公式在 Office
          打开后重算；打印分页请下载验收。
        </p>
        <Segmented
          className="login-portal-segmented mb-4"
          value={sheetIndex}
          options={file.layout.map((item, index) => ({ value: index, label: item.name }))}
          onChange={(next) => setSheetIndex(Number(next))}
        />
        {sheet?.truncated && (
          <p className="sys-field-hint">仅预览前 200 行、40 列；下载文件包含完整数据。</p>
        )}
        <div className="template-sheet-preview">
          <table>
            <colgroup>
              {sheet?.widths.map((width, index) => (
                <col key={index} style={{ width }} />
              ))}
            </colgroup>
            <tbody>
              {sheet?.rows.map((row, index) => (
                <tr key={index} style={{ height: `${row.height}pt` }}>
                  {row.cells.map((cell) => (
                    <td
                      key={cell.column}
                      rowSpan={cell.rowSpan}
                      colSpan={cell.colSpan}
                      style={cell.style as CSSProperties}
                    >
                      {cell.text}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
    setEditing(structuredClone(template));
    setTab("fields");
    setStyleTarget("default");
    setCapabilityId("");
    setValues(
      Object.fromEntries(
        template.spec.fields.map((field) => [field.key, sampleTemplateValue(field)]),
      ),
    );
  };
  const editable = editing?.status === "draft" && editing.createdBy === user?.id;
  const styleValue = (): TemplateStyle => {
    if (!editing) return {};
    if (styleTarget === "default") return editing.spec.defaultStyle ?? {};
    if (styleTarget.startsWith("sheet:"))
      return editing.spec.sheetStyles?.[styleTarget.slice(6)] ?? {};
    return editing.spec.locations.find((location) => location.id === styleTarget)?.style ?? {};
  };
  const updateStyle = (style: TemplateStyle) => {
    if (!editing) return;
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
            <article key={item.id} className="sys-card template-library-card" tabIndex={0} role="button" aria-label={`${item.status === "draft" ? "配置" : "查看"}模板：${item.name}`}
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
                  {item.status === "draft" ? "配置" : "查看"}
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
            ? `${editing.name} · ${editing.status === "published" ? "固定发布版本" : "模板草稿"}`
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
            <Segmented
              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented template-tabs"
              value={tab}
              options={[
                { value: "fields", label: "字段设置" },
                { value: "style", label: "格式配置" },
                { value: "preview", label: "试填与检查" },
              ]}
              onChange={(value) => setTab(String(value))}
            />
            {tab === "fields" && (
              <fieldset disabled={!editable} className="template-editor-fieldset grid gap-5">
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
                    替换草稿原件（会重新识别并重置字段和格式）
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
                {editing.kind === "excel" && <details className="template-region-settings"><summary><strong>明细区域与扩展方向</strong><span className="sys-info-tag">{editing.spec.excelRegions?.length ? `已识别 ${editing.spec.excelRegions.length} 个区域` : "默认按样板行向下展开"}</span></summary><section className="grid gap-3">
                  <p className="sys-field-hint">上传时自动识别并列明细和横向样板列，各列表可有不同条数。纵向标记横排在样板行、记录向下增加；横向标记竖排在样板列、记录向右增加。可在下方调整区域，方向须与原文件标记布局一致，目标区域应留空。</p>
                  <button type="button" disabled={busy} className="sys-btn sys-btn--default template-add-action" onClick={() => void action(async () => {
                    const excelRegions = inferTemplateExcelRegions(editing.spec);
                    if (!excelRegions.length) throw new Error("原文件中没有明细标记，请先添加 {{items[].name}} 等标记");
                    setEditing({ ...editing, spec: { ...editing.spec, excelRegions } });
                    void message.success(`已识别 ${excelRegions.length} 个明细区域，请保存草稿后试填或发布`);
                  })}><Search size={15} />重新识别区域</button>
                  {(editing.spec.excelRegions ?? []).map((region, index) => <div key={index} className="template-region-card"><div className="template-region-heading"><strong>区域 {index + 1}</strong><button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => setEditing({ ...editing, spec: { ...editing.spec, excelRegions: editing.spec.excelRegions!.filter((_, i) => i !== index) } })}>删除区域</button></div><div className="template-form-grid">
                    <div className="sys-field"><label className="sys-field-label">Sheet 页</label>
                    <TemplateSelect value={region.sheet} options={[...new Set(editing.spec.locations.map(l => l.sheet).filter(Boolean))].map(sheet => ({ value: sheet, label: sheet }))} onChange={sheet => setEditing({ ...editing, spec: { ...editing.spec, excelRegions: editing.spec.excelRegions!.map((r, i) => i === index ? { ...r, sheet } : r) } })} />
                    </div><div className="sys-field"><label className="sys-field-label">明细字段</label>
                    <TemplateSelect value={region.field} options={editing.spec.fields.filter(f => f.type === "table").map(f => ({ value: f.key, label: f.label || f.key }))} onChange={field => setEditing({ ...editing, spec: { ...editing.spec, excelRegions: editing.spec.excelRegions!.map((r, i) => i === index ? { ...r, field } : r) } })} />
                    </div>
                    <TemplateInput label="数据开始单元格" value={region.startCell} onChange={startCell => setEditing({ ...editing, spec: { ...editing.spec, excelRegions: editing.spec.excelRegions!.map((r, i) => i === index ? { ...r, startCell: startCell.toUpperCase() } : r) } })} />
                    <div className="sys-field"><label className="sys-field-label">记录扩展方向</label><TemplateSelect value={region.direction ?? "vertical"} options={[{ value: "vertical", label: "纵向（向下增加行）" }, { value: "horizontal", label: "横向（向右增加列）" }]} onChange={direction => setEditing({ ...editing, spec: { ...editing.spec, excelRegions: editing.spec.excelRegions!.map((r, i) => i === index ? { ...r, direction } : r) } })} /></div>
                  </div></div>)}
                  <button type="button" className="sys-btn sys-btn--default template-add-action" onClick={() => setEditing({ ...editing, spec: { ...editing.spec, excelRegions: [...(editing.spec.excelRegions ?? []), { sheet: "", field: "", startCell: "A2", direction: "vertical" }] } })}><Plus size={15} />添加明细区域</button>
                </section></details>}
                <TemplateFieldsEditor
                  lockedKeys
                  fields={editing.spec.fields}
                  onChange={(fields) =>
                    setEditing({ ...editing, spec: { ...editing.spec, fields } })
                  }
                />
              </fieldset>
            )}
            {tab === "style" && (
              <div className="grid gap-5">
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
                <fieldset disabled={!editable} className="template-editor-fieldset">
                  <TemplateStyleEditor
                    kind={editing.kind}
                    value={styleValue()}
                    onChange={updateStyle}
                  />
                </fieldset>
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
                        if (editable) await deliveryTemplateApi.save(tenantId, token, editing);
                        const file = await deliveryTemplateApi.preview(
                          tenantId,
                          token,
                          editing.id,
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
                                  editing.id,
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
                  保存草稿
                </button>
                <button
                  className="sys-btn sys-btn--primary"
                  disabled={busy}
                  onClick={() =>
                    void action(async () => {
                      if (token && tenantId && editing) {
                        await deliveryTemplateApi.save(tenantId, token, editing);
                        const result = await deliveryTemplateApi.publish(
                          tenantId,
                          token,
                          editing.id,
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
      {preview && (
        <TemplatePreview file={preview} kind={preview.kind} onClose={() => setPreview(null)} />
      )}
    </div>
  );
}
