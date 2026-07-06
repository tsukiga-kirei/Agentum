import { useEffect, useMemo, useState } from "react";
import {
  CRON_FIELD_LABELS,
  buildCronExpression,
  createDefaultCronConfig,
  formatFieldOptionLabel,
  getCronFieldOrder,
  listFieldOptions,
  parseCronExpression,
  type CronExpressionConfig,
  type CronFieldConfig,
  type CronFieldKey,
  type CronFieldMode,
} from "./cronExpression";

type CronExpressionGeneratorProps = {
  value: string;
  onChange: (expression: string) => void;
};

const modeOptions: { value: CronFieldMode; label: string; hint: string }[] = [
  { value: "every", label: "每单位", hint: "允许的通配符 [, - * /]" },
  { value: "range", label: "按周期", hint: "周期从" },
  { value: "step", label: "按步长", hint: "从" },
  { value: "specify", label: "指定", hint: "勾选具体值" },
];

export function CronExpressionGenerator({ value, onChange }: CronExpressionGeneratorProps) {
  const [activeField, setActiveField] = useState<CronFieldKey>("second");
  const [config, setConfig] = useState<CronExpressionConfig>(() => parseCronExpression(value));

  useEffect(() => {
    setConfig(parseCronExpression(value));
  }, [value]);

  const expressionPreview = useMemo(() => buildCronExpression(config), [config]);

  function updateField(field: CronFieldKey, patch: Partial<CronFieldConfig>) {
    setConfig((current) => {
      const next = {
        ...current,
        [field]: {
          ...current[field],
          ...patch,
        },
      };
      onChange(buildCronExpression(next));
      return next;
    });
  }

  function toggleSpecified(field: CronFieldKey, option: string) {
    const currentValues = config[field].specified;
    const nextValues = currentValues.includes(option)
      ? currentValues.filter((valueItem) => valueItem !== option)
      : [...currentValues, option];
    updateField(field, { mode: "specify", specified: nextValues });
  }

  const activeConfig = config[activeField];
  const fieldOptions = listFieldOptions(activeField);

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
        {modeOptions.map((option) => (
          <label key={option.value} className="cron-generator-mode">
            <input
              type="radio"
              name={`cron-mode-${activeField}`}
              checked={activeConfig.mode === option.value}
              onChange={() => updateField(activeField, { mode: option.value })}
            />
            <span className="cron-generator-mode-label">
              {option.label}
              {option.value === "range" ? (
                <span className="cron-generator-inline-inputs">
                  <span>{option.hint}</span>
                  <input
                    className="cron-generator-input"
                    value={activeConfig.rangeStart}
                    disabled={activeConfig.mode !== "range"}
                    onChange={(event) => updateField(activeField, { mode: "range", rangeStart: event.target.value })}
                  />
                  <span>到</span>
                  <input
                    className="cron-generator-input"
                    value={activeConfig.rangeEnd}
                    disabled={activeConfig.mode !== "range"}
                    onChange={(event) => updateField(activeField, { mode: "range", rangeEnd: event.target.value })}
                  />
                </span>
              ) : null}
              {option.value === "step" ? (
                <span className="cron-generator-inline-inputs">
                  <span>{option.hint}</span>
                  <input
                    className="cron-generator-input"
                    value={activeConfig.stepStart}
                    disabled={activeConfig.mode !== "step"}
                    onChange={(event) => updateField(activeField, { mode: "step", stepStart: event.target.value })}
                  />
                  <span>开始，每</span>
                  <input
                    className="cron-generator-input"
                    value={activeConfig.stepValue}
                    disabled={activeConfig.mode !== "step"}
                    onChange={(event) => updateField(activeField, { mode: "step", stepValue: event.target.value })}
                  />
                  <span>{CRON_FIELD_LABELS[activeField]}执行一次</span>
                </span>
              ) : null}
              {option.value === "every" ? <small>{option.hint}</small> : null}
            </span>
          </label>
        ))}

        {activeConfig.mode === "specify" ? (
          <div className="cron-generator-grid" aria-label={`指定${CRON_FIELD_LABELS[activeField]}`}>
            {fieldOptions.map((option) => {
              const checked = activeConfig.specified.includes(option);
              return (
                <label key={option} className={`cron-generator-grid-item ${checked ? "cron-generator-grid-item--checked" : ""}`}>
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => toggleSpecified(activeField, option)}
                  />
                  <span>{formatFieldOptionLabel(activeField, option)}</span>
                </label>
              );
            })}
          </div>
        ) : null}
      </div>

      <button
        type="button"
        className="sys-btn sys-btn--text sys-btn--sm"
        onClick={() => {
          const reset = createDefaultCronConfig();
          setConfig(reset);
          onChange(buildCronExpression(reset));
        }}
      >
        重置为默认
      </button>
    </div>
  );
}
