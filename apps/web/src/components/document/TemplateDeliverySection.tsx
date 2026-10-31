import { useEffect, useState } from "react";
import { Drawer, Pagination, Segmented } from "antd";
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
  TemplateSelect,
  TemplateStyleEditor,
  TemplateValueEditor,
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
  const token = useAuthStore((state) => state.token);
  const tenantId = useAuthStore((state) => state.activeRole?.tenantId);
  const theme = useAuthStore((state) => state.themeMode);
  const [items, setItems] = useState<DeliveryTemplate[]>([]);
  const [template, setTemplate] = useState<DeliveryTemplate | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState("");
  const [keyword, setKeyword] = useState("");
  const [error, setError] = useState("");
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
    setError("");
    void deliveryTemplateApi
      .list(tenantId, token, page, true, search)
      .then((result) => {
        if (!disposed) {
          setItems(result.items);
          setTotal(result.total);
        }
      })
      .catch((err) => {
        if (!disposed) setError(err instanceof Error ? err.message : "模板列表加载失败");
      });
    return () => {
      disposed = true;
    };
  }, [token, tenantId, page, search, library]);
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
          setError(err instanceof Error ? err.message : "模板读取失败");
        }
      });
    return () => {
      disposed = true;
    };
  }, [token, tenantId, id]);
  const selectTemplate = (next: DeliveryTemplate) => {
    if (next.kind !== kind) {
      setError("请选择与当前交付类型一致的模板");
      return;
    }
    onChange({ templateVersionId: next.id, templateBindings: [], templateLocationStyles: {} });
    setLibrary(false);
  };
  const options = variables.flatMap((variable) => {
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
      {error && (
        <div role="alert" className="template-error">
          {error}
        </div>
      )}
      <div className="template-section-heading">
        <strong>文件模板</strong>
        <button type="button" className="sys-btn sys-btn--default" onClick={() => setLibrary(true)}>
          <Library size={15} />
          上传 / 管理模板
        </button>
      </div>
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
          <Segmented
            className="login-portal-segmented"
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
              const selected = binding?.constant
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
                    options={[{ value: "constant", label: "固定内容" }, ...options]}
                    onChange={(value) => {
                      if (value === "constant")
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
                  {binding && !binding.constant && field.type === "table" && (
                    <div className="template-form-grid">
                      {field.columns.map((column) => {
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
                            {sourceField?.columns.length ? (
                              <TemplateSelect
                                value={binding.columnMappings?.[column.key] ?? column.key}
                                options={sourceField.columns.map((sourceColumn) => ({
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
                  {binding && !binding.constant && (
                    <div className="grid gap-3">
                      {(binding.additionalSources ?? []).map((extra, index) => (
                        <div className="flex gap-2" key={index}>
                          <TemplateSelect
                            value={JSON.stringify({
                              node: extra.sourceNodeId,
                              variable: extra.variable,
                              path: extra.path,
                            })}
                            options={options.filter(
                              (option) => !option.value.includes('"node":"system"'),
                            )}
                            onChange={(next) => {
                              const source = JSON.parse(next) as {
                                node: string;
                                variable: string;
                                path: string;
                              };
                              updateBinding(field.key, {
                                additionalSources: binding.additionalSources!.map((item, i) =>
                                  i === index
                                    ? {
                                        sourceNodeId: source.node,
                                        variable: source.variable,
                                        path: source.path,
                                      }
                                    : item,
                                ),
                              });
                            }}
                          />
                          <button
                            type="button"
                            className="sys-btn sys-btn--default"
                            onClick={() =>
                              updateBinding(field.key, {
                                additionalSources: binding.additionalSources!.filter(
                                  (_, i) => i !== index,
                                ),
                              })
                            }
                          >
                            移除
                          </button>
                        </div>
                      ))}
                      {["text", "markdown", "paragraphs", "table"].includes(field.type) && (
                        <button
                          type="button"
                          className="sys-btn sys-btn--default"
                          onClick={() =>
                            updateBinding(field.key, {
                              additionalSources: [
                                ...(binding.additionalSources ?? []),
                                {
                                  sourceNodeId: binding.sourceNodeId,
                                  variable: binding.variable,
                                  path: binding.path,
                                },
                              ],
                            })
                          }
                        >
                          追加内容来源（按顺序组合）
                        </button>
                      )}
                      <p className="sys-field-hint">
                        文字按空行连接，段落或明细列表按顺序追加；不会让 AI
                        重新改写，也不会按行号关联不同列表。
                      </p>
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
              <p className="sys-field-hint">
                以下是独立试填数据，不执行上游
                AI，不产生正式交付记录。用于验证内容结构、位置和格式。
              </p>
              {template.spec.fields.map((field) => (
                <TemplateValueEditor
                  key={field.key}
                  field={field}
                  value={values[field.key]}
                  onChange={(value) => setValues({ ...values, [field.key]: value })}
                />
              ))}
              <button
                type="button"
                disabled={busy || !capabilityId}
                className="sys-btn sys-btn--primary"
                onClick={async () => {
                  if (!token || !tenantId) return;
                  setBusy(true);
                  setError("");
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
                    setError(err instanceof Error ? err.message : "模板试填失败");
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
        rootClassName={getThemedDrawerRootClassName(theme)}
        onClose={() => setLibrary(false)}
        destroyOnHidden
      >
        <DeliveryTemplatesPanel onPublished={selectTemplate} />
      </Drawer>
      {preview && <TemplatePreview file={preview} kind={kind} onClose={() => setPreview(null)} />}
    </section>
  );
}
