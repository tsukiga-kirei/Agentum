export type CronFieldKey = "second" | "minute" | "hour" | "day" | "month" | "week";

export type CronFieldConfig = {
  every: boolean;
  specified: string[];
};

export type CronExpressionConfig = Record<CronFieldKey, CronFieldConfig>;

const FIELD_ORDER: CronFieldKey[] = ["second", "minute", "hour", "day", "month", "week"];

const FIELD_LIMITS: Record<CronFieldKey, { min: number; max: number; labels?: string[] }> = {
  second: { min: 0, max: 59 },
  minute: { min: 0, max: 59 },
  hour: { min: 0, max: 23 },
  day: { min: 1, max: 31 },
  month: { min: 1, max: 12 },
  week: {
    min: 0,
    max: 6,
    labels: ["日", "一", "二", "三", "四", "五", "六"],
  },
};

export const CRON_FIELD_LABELS: Record<CronFieldKey, string> = {
  second: "秒",
  minute: "分钟",
  hour: "小时",
  day: "日",
  month: "月",
  week: "星期",
};

function defaultFieldConfig(): CronFieldConfig {
  return { every: true, specified: [] };
}

export function createDefaultCronConfig(): CronExpressionConfig {
  return {
    second: { every: true, specified: [] },
    minute: { every: true, specified: [] },
    hour: { every: true, specified: [] },
    day: { every: true, specified: [] },
    month: { every: true, specified: [] },
    week: { every: true, specified: [] },
  };
}

export function isFieldEvery(field: CronFieldConfig) {
  return field.every;
}

/** 构建表达式时：未指定具体值则按「全部」输出 */
function isFieldWildcard(field: CronFieldConfig) {
  return field.every || field.specified.length === 0;
}

export function setFieldEvery(config: CronExpressionConfig, field: CronFieldKey, every: boolean): CronExpressionConfig {
  return {
    ...config,
    [field]: every ? { every: true, specified: [] } : { every: false, specified: [...config[field].specified] },
  };
}

export function toggleFieldSpecified(config: CronExpressionConfig, field: CronFieldKey, option: string): CronExpressionConfig {
  const current = config[field];
  let specified = current.specified;
  if (current.every) {
    // 从「全部」切换到指定：先视为全选，再取消当前项
    specified = listFieldOptions(field).filter((value) => value !== option);
  } else if (specified.includes(option)) {
    specified = specified.filter((value) => value !== option);
  } else {
    specified = [...specified, option];
  }
  if (specified.length === 0) {
    return setFieldEvery(config, field, true);
  }
  return {
    ...config,
    [field]: {
      every: false,
      specified: [...specified].sort((left, right) => Number(left) - Number(right)),
    },
  };
}

export function setFieldSpecified(config: CronExpressionConfig, field: CronFieldKey, specified: string[]): CronExpressionConfig {
  if (specified.length === 0) {
    return setFieldEvery(config, field, true);
  }
  return {
    ...config,
    [field]: {
      every: false,
      specified: [...specified].sort((left, right) => Number(left) - Number(right)),
    },
  };
}

function padValue(value: string, field: CronFieldKey) {
  if (field === "week") {
    return value;
  }
  return value.padStart(2, "0");
}

function formatFieldValue(field: CronFieldKey, config: CronFieldConfig): string {
  if (isFieldWildcard(config)) {
    return field === "week" ? "?" : "*";
  }
  return config.specified.map((value) => padValue(value, field)).join(",");
}

export function buildCronExpression(config: CronExpressionConfig): string {
  const day = formatFieldValue("day", config.day);
  const week = formatFieldValue("week", config.week);
  const weekPart = !isFieldWildcard(config.day) ? "?" : week === "*" ? "?" : week;
  return [
    formatFieldValue("second", config.second),
    formatFieldValue("minute", config.minute),
    formatFieldValue("hour", config.hour),
    day,
    formatFieldValue("month", config.month),
    weekPart,
  ].join(" ");
}

function expandRangeToken(token: string) {
  if (!token.includes("-")) {
    return [token];
  }
  const [startText, endText] = token.split("-");
  const start = Number(startText);
  const end = Number(endText);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) {
    return [];
  }
  const values: string[] = [];
  for (let value = start; value <= end; value += 1) {
    values.push(String(value));
  }
  return values;
}

function expandStepToken(token: string, field: CronFieldKey) {
  if (!token.includes("/")) {
    return [];
  }
  const [startText, stepText] = token.split("/");
  const step = Number(stepText);
  const start = startText === "*" ? FIELD_LIMITS[field].min : Number(startText);
  const end = FIELD_LIMITS[field].max;
  if (!Number.isFinite(step) || step <= 0 || !Number.isFinite(start)) {
    return [];
  }
  const values: string[] = [];
  for (let value = start; value <= end; value += step) {
    values.push(String(value));
  }
  return values;
}

function normalizeWeekValue(field: CronFieldKey, value: string) {
  if (field !== "week") {
    return String(Number(value));
  }
  const upper = value.toUpperCase();
  const weekMap: Record<string, string> = {
    SUN: "0",
    MON: "1",
    TUE: "2",
    WED: "3",
    THU: "4",
    FRI: "5",
    SAT: "6",
    "7": "0",
  };
  return weekMap[upper] ?? String(Number(value));
}

function parseFieldToken(field: CronFieldKey, token: string): CronFieldConfig {
  if (!token || token === "*" || token === "?") {
    return defaultFieldConfig();
  }
  if (token.includes("/")) {
    const values = expandStepToken(token, field).map((value) => normalizeWeekValue(field, value)).filter(Boolean);
    return values.length > 0 ? { every: false, specified: values } : defaultFieldConfig();
  }
  const values = token
    .split(",")
    .flatMap((part) => expandRangeToken(part.trim()))
    .map((value) => normalizeWeekValue(field, value.trim()))
    .filter(Boolean);
  if (values.length === 0) {
    return defaultFieldConfig();
  }
  return { every: false, specified: [...new Set(values)].sort((left, right) => Number(left) - Number(right)) };
}

export function parseCronExpression(expression: string): CronExpressionConfig {
  const parts = expression.trim().split(/\s+/);
  const config = createDefaultCronConfig();
  if (parts.length < 6) {
    return config;
  }
  const [second, minute, hour, day, month, week] = parts;
  config.second = parseFieldToken("second", second);
  config.minute = parseFieldToken("minute", minute);
  config.hour = parseFieldToken("hour", hour);
  config.day = parseFieldToken("day", day);
  config.month = parseFieldToken("month", month);
  config.week = parseFieldToken("week", week);
  if (week === "?") {
    config.week = defaultFieldConfig();
  }
  if (day === "?") {
    config.day = defaultFieldConfig();
  }
  return config;
}

export function listFieldOptions(field: CronFieldKey) {
  const { min, max } = FIELD_LIMITS[field];
  const options: string[] = [];
  for (let value = min; value <= max; value += 1) {
    options.push(String(value));
  }
  return options;
}

export function formatFieldOptionLabel(field: CronFieldKey, value: string) {
  const labels = FIELD_LIMITS[field].labels;
  if (field === "week" && labels) {
    return `星期${labels[Number(value)] ?? value}`;
  }
  if (!labels) {
    return value.padStart(2, "0");
  }
  return value.padStart(2, "0");
}

export function isValidCronExpression(expression: string) {
  const parts = expression.trim().split(/\s+/);
  return parts.length === 6 && parts.every((part) => part.length > 0);
}

export function getCronFieldOrder() {
  return FIELD_ORDER;
}
