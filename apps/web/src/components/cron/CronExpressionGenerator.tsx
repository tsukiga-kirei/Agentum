import { useEffect, useMemo, useState } from "react";
import {
  CRON_FIELD_LABELS,
  buildCronExpression,
  formatFieldOptionLabel,
  getCronFieldOrder,
  listFieldOptions,
  parseCronExpression,
  setFieldEvery,
  toggleFieldSpecified,
  type CronExpressionConfig,
  type CronFieldKey,
} from "./cronExpression";

type CronExpressionGeneratorProps = {
  value: string;
  onChange: (expression: string) => void;
};

export function CronExpressionGenerator({ value, onChange }: CronExpressionGeneratorProps) {
  const [activeField, setActiveField] = useState<CronFieldKey>("second");
  const [config, setConfig] = useState<CronExpressionConfig>(() => parseCronExpression(value));

  useEffect(() => {
    setConfig(parseCronExpression(value));
  }, [value]);

  const expressionPreview = useMemo(() => buildCronExpression(config), [config]);
  const fieldOptions = listFieldOptions(activeField);
  const fieldIsEvery = config[activeField].every;

  function isOptionChecked(option: string) {
    const field = config[activeField];
    return field.every || field.specified.includes(option);
  }

  function applyConfig(next: CronExpressionConfig) {
    setConfig(next);
    onChange(buildCronExpression(next));
  }

  function handleToggleAll(checked: boolean) {
    applyConfig(setFieldEvery(config, activeField, checked));
  }

  function handleToggleOption(option: string) {
    applyConfig(toggleFieldSpecified(config, activeField, option));
  }

  return (
    <div className="cron-generator">
      <div className="cron-generator-head">
        <span className="cron-generator-title">Cron 表达式生成器</span>
        <code className="cron-generator-preview">{expressionPreview}</code>
      </div>

      <div className="cron-generator-tabs" role="tablist" aria-label="Cron 字段">
        {getCronFieldOrder().map((field) => (
          <button
            key={field}
            type="button"
            role="tab"
            aria-selected={activeField === field}
            className={`cron-generator-tab ${activeField === field ? "cron-generator-tab--active" : ""}`}
            onClick={() => setActiveField(field)}
          >
            {CRON_FIELD_LABELS[field]}
          </button>
        ))}
      </div>

      <div className="cron-generator-panel">
        <p className="cron-generator-hint">
          勾选要执行的{CRON_FIELD_LABELS[activeField]}；选择「全部」表示该时间维度不限制。
        </p>
        <label className={`cron-generator-all ${fieldIsEvery ? "cron-generator-all--checked" : ""}`}>
          <input
            type="checkbox"
            checked={fieldIsEvery}
            onChange={(event) => handleToggleAll(event.target.checked)}
          />
          <span>全部</span>
        </label>
        <div
          className={`cron-generator-grid ${activeField === "week" ? "cron-generator-grid--week" : ""}`}
          aria-label={`指定${CRON_FIELD_LABELS[activeField]}`}
        >
          {fieldOptions.map((option) => {
            const checked = isOptionChecked(option);
            return (
              <label key={option} className={`cron-generator-grid-item ${checked ? "cron-generator-grid-item--checked" : ""}`}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => handleToggleOption(option)}
                />
                <span>{formatFieldOptionLabel(activeField, option)}</span>
              </label>
            );
          })}
        </div>
      </div>
    </div>
  );
}
