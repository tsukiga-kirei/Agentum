import { useEffect, useState } from "react";
import { Drawer } from "antd";
import { Save } from "lucide-react";
import { useAuthStore } from "../../stores/authStore";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import type { TemplateField } from "../../types/delivery-template";
import { TemplateValueEditor } from "../document/TemplateEditors";

/** 仅新增结构化模式使用字段表单，原 Markdown 修改弹窗保持原样。 */
export function StructuredResultEditDrawer({
  open,
  initialValue,
  fields,
  onClose,
  onSave,
}: {
  open: boolean;
  initialValue: string;
  fields: TemplateField[];
  onClose: () => void;
  onSave: (value: string) => Promise<void>;
}) {
  const theme = useAuthStore((state) => state.themeMode);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (open) {
      try {
        setValues(initialValue ? (JSON.parse(initialValue) as Record<string, unknown>) : {});
        setError("");
      } catch {
        setValues({});
        setError("正式业务字段读取失败，请刷新后重试");
      }
    }
  }, [open, initialValue]);

  const handleSave = async () => {
    if (saving) return;
    setSaving(true);
    setError("");
    try {
      await onSave(JSON.stringify(values));
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存业务字段失败，请稍后重试");
    } finally {
      setSaving(false);
    }
  };
  return (
    <Drawer
      open={open}
      title="修改业务字段"
      size={800}
      rootClassName={getThemedDrawerRootClassName(theme)}
      onClose={onClose}
      destroyOnHidden
    >
      <div className="sys-drawer-section grid gap-5">
        <p className="sys-field-hint">
          修改后会校验字段并同步更新正式数据和可阅读结果；依赖此结果的后续步骤需要重新执行。
        </p>
        {error && (
          <div className="template-error" role="alert">
            {error}
          </div>
        )}
        {fields.map((field) => (
          <TemplateValueEditor
            key={field.key}
            field={field}
            value={values[field.key]}
            onChange={(value) => setValues({ ...values, [field.key]: value })}
          />
        ))}
      </div>
      <div className="sys-drawer-footer">
        <button type="button" className="sys-btn sys-btn--default" disabled={saving} onClick={onClose}>
          取消
        </button>
        <div className="sys-drawer-footer-right">
          <button
            type="button"
            className="sys-btn sys-btn--primary"
            disabled={Boolean(error) || saving}
            onClick={() => void handleSave()}
          >
            <Save size={15} />
            {saving ? "正在保存…" : "保存字段"}
          </button>
        </div>
      </div>
    </Drawer>
  );
}
