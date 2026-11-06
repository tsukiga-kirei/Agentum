import { useEffect, useState } from "react";
import { App, Drawer, Pagination } from "antd";
import { Eye, Library, Search } from "lucide-react";
import { deliveryTemplateApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import type {
  DeliveryTemplate,
  TemplateBinding,
  TemplateSource,
  TemplateStyle,
  TemplateWorkbookSheet,
} from "../../types/delivery-template";
import { sampleTemplateValue, templateFieldTypes } from "../../types/delivery-template";
import { DeliveryTemplatesPanel, TemplatePreview } from "./DeliveryTemplatesPanel";
import {
  TemplateInput,
  TemplateTabs,
  TemplateSelect,
  TemplateStyleEditor,
  TemplateValueEditor,
  TemplateValuesEditor,
} from "./TemplateEditors";

export function TemplateDeliverySection({
  config,
  variables,
  kind,
  capabilityId,
  onChange,
}: {
  config: Record<string, unknown>;
  variables: TemplateSource[];
  kind: "word" | "excel";
  capabilityId: string;
  onChange: (patch: Record<string, unknown>) => void;
}) {
  const { message } = App.useApp();
  const token = useAuthStore((state) => state.token);
  const user = useAuthStore((state) => state.user);
  const activeRole = useAuthStore((state) => state.activeRole);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const theme = useAuthStore((state) => state.themeMode);
  const [items, setItems] = useState<DeliveryTemplate[]>([]);
  const [template, setTemplate] = useState<DeliveryTemplate | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState("");
  const [keyword, setKeyword] = useState("");
  const [busy, setBusy] = useState(false);
  const [library, setLibrary] = useState(false);
  const [tab, setTab] = useState("bindings");
  const [locationId, setLocationId] = useState("");
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [preview, setPreview] = useState<{
    blob: Blob;
    fileName: string;
    layout?: TemplateWorkbookSheet[];
  } | null>(null);
  const id = String(config.templateVersionId ?? "");
  const bindings = (config.templateBindings ?? []) as TemplateBinding[];
  const styles = (config.templateLocationStyles ?? {}) as Record<string, TemplateStyle>;
  useEffect(() => {
    if (!token || !tenantId) return;
    let disposed = false;
    void deliveryTemplateApi
      .list(tenantId, token, page, true, search)
      .then((result) => {
        if (!disposed) {
          setItems(result.items);
          setTotal(result.total);
        }
      })
      .catch((err) => {
        if (!disposed) void message.error(err instanceof Error ? err.message : "模板列表加载失败", 6);
      });
    return () => {
      disposed = true;
    };
  }, [message, token, tenantId, page, search, library]);
  useEffect(() => {
    if (!token || !tenantId || !id) {
      setTemplate(null);
      return;
    }
    let disposed = false;
    void deliveryTemplateApi
      .get(tenantId, token, id)
      .then((result) => {
        if (!disposed) {
          setTemplate(result);
          setLocationId(result.spec.locations[0]?.id ?? "");
          setValues(
            Object.fromEntries(
              result.spec.fields.map((field) => [field.key, sampleTemplateValue(field)]),
            ),
          );
        }
      })
      .catch((err) => {
        if (!disposed) {
          setTemplate(null);
          void message.error(err instanceof Error ? err.message : "模板读取失败", 6);
        }
      });
    return () => {
      disposed = true;
    };
  }, [message, token, tenantId, id]);
  const selectTemplate = (next: DeliveryTemplate) => {
    if (next.kind !== kind) {
      void message.error("请选择与当前交付类型一致的模板", 6);
      return;
    }
    onChange({ templateVersionId: next.id, templateBindings: [], templateLocationStyles: {} });
    setLibrary(false);
  };
  const options = variables.filter(variable => !variable.datasetPath).flatMap((variable) => {
    const base = {
      node: variable.sourceNodeId.startsWith("__") ? "system" : variable.sourceNodeId,
      variable: variable.name,
    };
    const root = {
      value: JSON.stringify({ ...base, path: "" }),
      label: `${variable.sourceNodeName} → ${variable.name}（${variable.type}）`,
    };
    return [
      root,
      ...(variable.fields ?? []).map((field) => ({
        value: JSON.stringify({ ...base, path: field.key }),
        label: `${variable.sourceNodeName} → ${variable.name} → ${field.label || field.key}`,
      })),
    ];
  });
  const updateBinding = (field: string, patch: Partial<TemplateBinding>) => {
    const current = bindings.find((binding) => binding.field === field) ?? {
      field,
      sourceNodeId: "",
      variable: "",
      path: "",
      constant: false,
    };
    onChange({
      templateBindings: [
        ...bindings.filter((binding) => binding.field !== field),
        { ...current, ...patch },
      ],
    });
  };
  return (
    <section className="template-binding-list">
      <div className="template-section-heading">
        <strong>文件模板</strong>
        <button type="button" className="sys-btn sys-btn--default" onClick={() => setLibrary(true)}>
          <Library size={15} />
          上传 / 管理模板
        </button>
      </div>
      <p className="sys-field-hint">
        当前交付类型为 {kind === "excel" ? "Excel（.xlsx）" : "Word（.docx）"}。
        请先在“上传 / 管理模板”中上传、配置并发布，再选择对应类型的已发布模板、绑定内容来源。
        {kind === "word" && "如需填写 Excel，请先将交付能力切换为 Excel 工作簿。"}
      </p>
      <details className="sys-field-hint">
        <summary className="cursor-pointer text-[var(--color-text-primary)]">
          查看模板填写规则与数据来源说明
        </summary>
        <div className="mt-3 grid gap-3">
          <p>
            普通字段使用 {"{{company_name}}"}；明细使用 {"{{items[].name}}"}、
            {"{{items[].amount}}"} 等标记，每列分别设置。字段标识以小写英文字母开头，
            仅包含小写字母、数字和下划线。修改原文件后，请保存并重新选择文件上传。
          </p>
          {kind === "excel" ? (
            <>
              <p>
                每个 Sheet 分别填写。明细从样板行按来源顺序向下展开，沿用样板单元格格式；
                单个普通字段标记只填当前单元格；需要向右扩展时，在模板管理中选择“横向”，字段标记竖排在样板列。
                请在模板管理中设置金额、日期等字段类型，并将明细条目上限设为足够容纳实际数据量。
              </p>
              <p>
                同一 Sheet 左右并列的不同明细使用不同标识，上传时会自动识别各自的独立区域，允许不同条数。
                可在模板管理中检查各字段位置、方向与扩展空间。推移模式会移动后续整行或整列；固定模式仅使用空白，覆盖时失败。
              </p>
              <p>
                汇总页可直接引用后面明细，原有公式保留并在所有数据写完后统一计算。未知行数优先使用 Excel 表格列名，或模板字段位置显示的命名范围；表格和命名范围会随实际条数更新。
                计算列会按记录复制，错误引用、循环引用或无法计算的公式会指出具体位置并阻止生成。
              </p>
            </>
          ) : (
            <p>
              普通字段放在正文或普通表格中；明细各列标记放在同一表格样板行，按来源记录重复生成行。
              同一样板行只使用一个明细列表，暂不支持文本框填写和嵌套重复区域。
            </p>
          )}
          <p>
            明细可选择上游结构化结果，也可在“绑定 MCP 数据集”中选择取数智能体及具体工具结果，并映射各列。
            MCP 工具需先配置保存数据集；交付时读取完整明细，不需要模型重新抄写，也不会在交付步骤重新调用 MCP。
          </p>
        </div>
      </details>
      <div className="flex gap-2">
        <div className="sys-field-input-wrap flex-1">
          <Search size={15} className="sys-field-prefix" />
          <input
            aria-label="搜索可用模板"
            className="sys-field-input"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索模板名称"
          />
        </div>
        <button
          type="button"
          className="sys-btn sys-btn--default"
          onClick={() => {
            setPage(1);
            setSearch(keyword);
          }}
        >
          搜索
        </button>
      </div>
      <TemplateSelect
        value={id || undefined}
        options={[
          ...(template && !items.some((item) => item.id === id)
            ? [{ value: id, label: `${template.name} · 固定版本` }]
            : []),
          ...items
            .filter((item) => item.kind === kind)
            .map((item) => ({ value: item.id, label: `${item.name} · v${item.version} · 已发布` })),
        ]}
        onChange={(next) => {
          const selected = items.find((item) => item.id === next);
          if (selected) selectTemplate(selected);
        }}
        placeholder="选择已发布的模板"
      />
      <Pagination
        size="small"
        current={page}
        total={total}
        pageSize={10}
        showSizeChanger={false}
        onChange={setPage}
      />
      <TemplateInput
        label="文件名模板"
        value={String(config.fileNameTemplate ?? "")}
        onChange={(fileNameTemplate) => onChange({ fileNameTemplate })}
        placeholder={`交付文件-{{runNumber}}.${kind === "word" ? "docx" : "xlsx"}`}
      />
      {template && (
        <>
          <p className="sys-field-hint">
            已固定模板版本。模板更新不会自动替换本流程；更换模板后需重新绑定字段。
          </p>
          <TemplateTabs
            value={tab}
            options={[
              { value: "bindings", label: "内容来源" },
              { value: "style", label: "位置格式" },
              { value: "preview", label: "试填检查" },
            ]}
            onChange={(next) => setTab(String(next))}
          />
          {tab === "bindings" &&
            template.spec.fields.map((field) => {
              const binding = bindings.find((item) => item.field === field.key);
              const selected = binding?.variable === "mcp_datasets" ? "dataset" : binding?.constant
                ? "constant"
                : binding?.variable
                  ? JSON.stringify({
                      node: binding.sourceNodeId,
                      variable: binding.variable,
                      path: binding.path,
                    })
                  : undefined;
              return (
                <div className="template-field-card" key={field.key}>
                  <div className="template-section-heading">
                    <strong>
                      {field.label || field.key}
                      {field.required ? " *" : ""}
                    </strong>
                    <small className="text-[var(--color-text-secondary)]">
                      {templateFieldTypes.find((type) => type.value === field.type)?.label}
                    </small>
                  </div>
                  <p className="sys-field-hint">
                    {field.description ||
                      "从已有输入或任意上游智能体结果中取值。同一个结果可以用于多个填写位置。"}
                  </p>
                  <TemplateSelect
                    value={selected}
                    options={[{ value: "constant", label: "固定内容" }, ...(field.type === "table" ? [{ value: "dataset", label: "MCP 工具明细结果" }] : []), ...options]}
                    onChange={(value) => {
                      if (value === "dataset") updateBinding(field.key, { constant: false, sourceNodeId: "", variable: "mcp_datasets", path: "", columnMappings: {} });
                      else if (value === "constant")
                        updateBinding(field.key, {
                          constant: true,
                          sourceNodeId: "",
                          variable: "",
                          path: "",
                          value: sampleTemplateValue(field),
                        });
                      else {
                        const source = JSON.parse(value) as {
                          node: string;
                          variable: string;
                          path: string;
                        };
                        updateBinding(field.key, {
                          constant: false,
                          sourceNodeId: source.node,
                          variable: source.variable,
                          path: source.path,
                          value: undefined,
                        });
                      }
                    }}
                    placeholder="选择来源节点及字段"
                  />
                  {field.type === "table" && binding?.variable === "mcp_datasets" && <div className="template-source-panel">
                    <p className="sys-field-hint">选择上游智能体及其 MCP 工具结果。只有配置了保存明细结果的工具会出现在列表中；本次执行必须实际产生该结果，交付不会重新调用工具。</p>
                    <TemplateSelect value={variables.some(v => v.sourceNodeId === binding.sourceNodeId && v.datasetPath === binding.path) ? JSON.stringify({ node: binding.sourceNodeId, path: binding.path }) : undefined} options={variables.filter(v => v.datasetPath).map(v => ({ value: JSON.stringify({ node: v.sourceNodeId, path: v.datasetPath }), label: v.sourceNodeName }))} placeholder={binding.path ? "原绑定的工具结果已变更，请重新选择" : "选择步骤 → 智能体 → MCP → 工具结果"} onChange={value => { const source = JSON.parse(value); updateBinding(field.key, { sourceNodeId: source.node, path: source.path, columnMappings: {} }); }} />
                    {!variables.some(v => v.datasetPath) && <p className="sys-field-hint">上游暂无可绑定的工具结果。请先为相应智能体配置 MCP，并在系统管理中设置该工具保存明细结果。</p>}
                  </div>}
                  {binding && !binding.constant && field.type === "table" && (
                    <div className="template-form-grid">
                      {field.columns.map((column) => {
                        const datasetColumns = variables.find(v => v.sourceNodeId === binding.sourceNodeId && v.datasetPath === binding.path)?.datasetColumns;
                        const sourceField = variables
                          .find(
                            (variable) =>
                              variable.name === binding.variable &&
                              variable.sourceNodeId === binding.sourceNodeId,
                          )
                          ?.fields?.find((candidate) => candidate.key === binding.path);
                        return (
                          <div className="sys-field" key={column.key}>
                            <label className="sys-field-label">
                              数据列：{column.label || column.key}
                            </label>
                            {(datasetColumns?.length || sourceField?.columns.length) ? (
                              <TemplateSelect
                                value={binding.columnMappings?.[column.key] ?? column.key}
                                options={(sourceField?.columns ?? datasetColumns?.map(key => ({ key, label: key })) ?? []).map((sourceColumn) => ({
                                  value: sourceColumn.key,
                                  label: sourceColumn.label || sourceColumn.key,
                                }))}
                                onChange={(path) =>
                                  updateBinding(field.key, {
                                    columnMappings: {
                                      ...binding.columnMappings,
                                      [column.key]: path,
                                    },
                                  })
                                }
                              />
                            ) : (
                              <TemplateInput
                                label="来源列字段"
                                value={binding.columnMappings?.[column.key] ?? column.key}
                                onChange={(path) =>
                                  updateBinding(field.key, {
                                    columnMappings: {
                                      ...binding.columnMappings,
                                      [column.key]: path,
                                    },
                                  })
                                }
                              />
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                  {binding?.constant && (
                    <TemplateValueEditor
                      field={field}
                      value={binding.value}
                      onChange={(value) => updateBinding(field.key, { value })}
                    />
                  )}
                </div>
              );
            })}
          {tab === "style" && (
            <>
              <TemplateSelect
                value={locationId}
                options={template.spec.locations.map((location) => ({
                  value: location.id,
                  label: location.label,
                }))}
                onChange={setLocationId}
              />
              <p className="sys-field-hint">
                这里的设置仅覆盖本交付项选定位置；空白沿用模板、Sheet 或区域已有格式。
              </p>
              <TemplateStyleEditor
                kind={kind}
                value={styles[locationId] ?? {}}
                onChange={(style) =>
                  onChange({ templateLocationStyles: { ...styles, [locationId]: style } })
                }
              />
            </>
          )}
          {tab === "preview" && (
            <>
              <div className="template-test-intro"><div><strong>填写试验数据</strong><p className="sys-field-hint">不执行上游 AI、不产生正式交付记录，仅检查内容、位置和格式。</p></div><span className="sys-info-tag">{template.spec.fields.length} 个字段</span></div>
              <TemplateValuesEditor fields={template.spec.fields} values={values} onChange={setValues} />
              <button
                type="button"
                disabled={busy || !capabilityId}
                className="sys-btn sys-btn--primary"
                onClick={async () => {
                  if (!token || !tenantId) return;
                  setBusy(true);
                  try {
                    const body = {
                      capabilityId,
                      values,
                      locationStyles: styles,
                      fileName: "模板试填",
                    };
                    const file = await deliveryTemplateApi.preview(tenantId, token, id, body);
                    setPreview({
                      ...file,
                      layout:
                        kind === "excel"
                          ? await deliveryTemplateApi.previewLayout(tenantId, token, id, body)
                          : undefined,
                    });
                  } catch (err) {
                    // 配置及预览均在抽屉中操作，失败统一在顶部显示，避免被滚动位置或其他抽屉遮住。
                    void message.error(err instanceof Error ? err.message : "模板试填失败", 6);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                <Eye size={15} />
                {busy ? "正在生成" : "生成试填预览"}
              </button>
            </>
          )}
        </>
      )}
      <Drawer
        open={library}
        title="交付模板"
        size={1100}
        rootClassName={getThemedDrawerRootClassName(theme, "template-config-drawer")}
        onClose={() => setLibrary(false)}
        destroyOnHidden
      >
        <div className="sys-drawer-section template-library-scroll"><DeliveryTemplatesPanel onPublished={selectTemplate} /></div>
        <div className="sys-drawer-footer"><div className="sys-drawer-footer-right"><button type="button" className="sys-btn sys-btn--default" onClick={() => setLibrary(false)}>关闭</button></div></div>
      </Drawer>
      {preview && <TemplatePreview file={preview} kind={kind} onClose={() => setPreview(null)} />}
    </section>
  );
}
