export type CronFieldKey = "second" | "minute" | "hour" | "day" | "month" | "week";
export type CronFieldMode = "every" | "range" | "step" | "specify";

export type CronFieldConfig = {
  mode: CronFieldMode;
  rangeStart: string;
  rangeEnd: string;
  stepStart: string;
  stepValue: string;
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
  return {
    mode: "every",
    rangeStart: "0",
    rangeEnd: "0",
    stepStart: "0",
    stepValue: "1",
    specified: [],
  };
}

export function createDefaultCronConfig(): CronExpressionConfig {
  return {
    second: { ...defaultFieldConfig(), mode: "every" },
    minute: { ...defaultFieldConfig(), mode: "every" },
    hour: { ...defaultFieldConfig(), mode: "every" },
    day: { ...defaultFieldConfig(), mode: "every" },
    month: { ...defaultFieldConfig(), mode: "every" },
    week: { ...defaultFieldConfig(), mode: "every", specified: [] },
  };
}

function padValue(value: string, field: CronFieldKey) {
  if (field === "week") {
    return value;
  }
  return value.padStart(2, "0");
}

function formatFieldValue(field: CronFieldKey, config: CronFieldConfig): string {
  switch (config.mode) {
    case "every":
      return "*";
    case "range":
      return `${config.rangeStart}-${config.rangeEnd}`;
    case "step":
      return `${config.stepStart}/${config.stepValue}`;
    case "specify": {
      const values = [...config.specified].sort((left, right) => Number(left) - Number(right));
      if (values.length === 0) {
        return field === "week" ? "?" : "*";
      }
      return values.map((value) => padValue(value, field)).join(",");
    }
    default:
      return "*";
  }
}

export function buildCronExpression(config: CronExpressionConfig): string {
  const day = formatFieldValue("day", config.day);
  const week = formatFieldValue("week", config.week);
  const dayPart = day !== "*" && week !== "*" && week !== "?" ? day : day;
  const weekPart = dayPart !== "*" && dayPart !== "?" ? "?" : week === "*" ? "?" : week;
  return [
    formatFieldValue("second", config.second),
    formatFieldValue("minute", config.minute),
    formatFieldValue("hour", config.hour),
    dayPart,
    formatFieldValue("month", config.month),
    weekPart,
  ].join(" ");
}

function parseFieldToken(field: CronFieldKey, token: string): CronFieldConfig {
  const config = defaultFieldConfig();
  if (!token || token === "*" || token === "?") {
    config.mode = "every";
    return config;
  }
  if (token.includes("/")) {
    const [start, step] = token.split("/");
    config.mode = "step";
    config.stepStart = start === "*" ? "0" : start;
    config.stepValue = step || "1";
    return config;
  }
  if (token.includes("-") && !token.includes(",")) {
    const [start, end] = token.split("-");
    config.mode = "range";
    config.rangeStart = start;
    config.rangeEnd = end;
    return config;
  }
  if (token.includes(",") || /^\d+$/.test(token) || /^[A-Z]{3}$/i.test(token)) {
    config.mode = "specify";
    config.specified = token.split(",").map((value) => normalizeWeekValue(field, value.trim())).filter(Boolean);
    return config;
  }
  config.mode = "every";
  return config;
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
    config.week.mode = "every";
  }
  if (day === "?") {
    config.day.mode = "every";
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
  if (!labels) {
    return value.padStart(2, "0");
  }
  return `${value.padStart(2, "0")}（周${labels[Number(value)] ?? value}）`;
}

export function isValidCronExpression(expression: string) {
  const parts = expression.trim().split(/\s+/);
  return parts.length === 6 && parts.every((part) => part.length > 0);
}

export function getCronFieldOrder() {
  return FIELD_ORDER;
}
