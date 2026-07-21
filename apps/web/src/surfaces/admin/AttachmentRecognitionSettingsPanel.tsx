import { useEffect, useMemo, useState } from "react";
import { Checkbox, Segmented, Select, Spin, Switch, message } from "antd";
import { ChevronDown, FileSearch, KeyRound, PlayCircle, Save, ServerCog } from "lucide-react";
import { AgentumApiError, systemApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AttachmentRecognitionSettings, UpdateAttachmentRecognitionSettingsRequest } from "../../types/system";

const DEFAULT_COMPLEX_EXTENSIONS = "pdf,png,jpg,jpeg,bmp,gif,tiff,webp,docx,xlsx,txt";
const BACKEND_OPTIONS = [
  { value: "pipeline", label: "Pipeline（pipeline）" },
  { value: "vlm-auto-engine", label: "VLM 自动引擎（vlm-auto-engine）" },
  { value: "vlm-http-client", label: "VLM HTTP 客户端（vlm-http-client）" },
  { value: "hybrid-auto-engine", label: "混合自动引擎（hybrid-auto-engine）" },
  { value: "hybrid-http-client", label: "混合 HTTP 客户端（hybrid-http-client）" },
];
const selectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };

function parseExtensions(value: string): string[] {
  return Array.from(new Set(value
    .split(/[，,\s]+/)
    .map((item) => item.trim().toLowerCase().replace(/^\./, ""))
    .filter(Boolean)));
}

export function AttachmentRecognitionSettingsPanel() {
  const token = useAuthStore((state) => state.token);
  const [messageApi, contextHolder] = message.useMessage();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [settings, setSettings] = useState<AttachmentRecognitionSettings | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [clearApiKey, setClearApiKey] = useState(false);
  const [extensionValues, setExtensionValues] = useState(() => parseExtensions(DEFAULT_COMPLEX_EXTENSIONS));

  useEffect(() => {
    if (!token) return;
    setLoading(true);
    systemApi.getAttachmentRecognitionSettings(token)
      .then((result) => {
        setSettings(result);
        setExtensionValues(result.mineruSupportedExtensions);
      })
      .catch((error) => messageApi.error(error instanceof AgentumApiError ? error.message : "附件识别配置加载失败"))
      .finally(() => setLoading(false));
  }, [messageApi, token]);

  const extensions = useMemo(() => parseExtensions(extensionValues.join(",")), [extensionValues]);

  function patch<K extends keyof AttachmentRecognitionSettings>(key: K, value: AttachmentRecognitionSettings[K]) {
    setSettings((current) => current ? { ...current, [key]: value } : current);
  }

  function buildRequest(): UpdateAttachmentRecognitionSettingsRequest | null {
    if (!settings) return null;
    return {
      recognitionEnabled: settings.recognitionEnabled,
      recognitionEngine: settings.recognitionEngine,
      maxFileSizeMb: settings.maxFileSizeMb,
      maxFilesPerField: settings.maxFilesPerField,
      maxExtractedChars: settings.maxExtractedChars,
      retentionPolicy: settings.retentionPolicy,
      retentionDays: settings.retentionDays,
      mineruSupportedExtensions: extensions,
      mineruEndpoint: settings.mineruEndpoint,
      mineruApiKey: apiKey || undefined,
      clearMineruApiKey: clearApiKey,
      mineruBackend: settings.mineruBackend,
      mineruParseMethod: settings.mineruParseMethod,
      mineruLanguage: settings.mineruLanguage,
      mineruEnableFormula: settings.mineruEnableFormula,
      mineruEnableTable: settings.mineruEnableTable,
      mineruConnectTimeoutSeconds: settings.mineruConnectTimeoutSeconds,
      mineruReadTimeoutSeconds: settings.mineruReadTimeoutSeconds,
    };
  }

  async function save() {
    if (!token) return;
    const body = buildRequest();
    if (!body) return;
    if (body.recognitionEngine === "mineru" && !body.mineruEndpoint?.trim()) {
      messageApi.error("复杂识别必须填写 MinerU 服务地址");
      return;
    }
    if (body.mineruSupportedExtensions.length === 0) {
      messageApi.error("至少填写一个复杂识别扩展名");
      return;
    }
    setSaving(true);
    try {
      const result = await systemApi.updateAttachmentRecognitionSettings(token, body);
      setSettings(result);
      setExtensionValues(result.mineruSupportedExtensions);
      setApiKey("");
      setClearApiKey(false);
      messageApi.success("附件识别配置已保存");
    } catch (error) {
      messageApi.error(error instanceof AgentumApiError ? error.message : "附件识别配置保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function testConnection() {
    if (!token || !settings?.mineruEndpoint?.trim()) {
      messageApi.error("请先填写 MinerU 服务地址");
      return;
    }
    setTesting(true);
    try {
      const result = await systemApi.testAttachmentRecognitionConnection(token, {
        mineruEndpoint: settings.mineruEndpoint,
        mineruApiKey: apiKey || undefined,
        useSavedApiKey: !clearApiKey,
        connectTimeoutSeconds: settings.mineruConnectTimeoutSeconds,
      });
      messageApi.success(`${result.summary}，耗时 ${result.latencyMs} ms`);
    } catch (error) {
      messageApi.error(error instanceof AgentumApiError ? error.message : "MinerU 连接测试失败");
    } finally {
      setTesting(false);
    }
  }

  return (
    <div className="sys-fade-in attachment-settings-panel">
      {contextHolder}
      <Spin spinning={loading}>
        {settings ? (
          <div className="space-y-4">
            <section className="sys-section-card">
              <div className="sys-section-card-header">
                <div>
                  <h3><FileSearch size={17} className="attachment-section-icon" /> 附件识别</h3>
                  <p>配置输入节点附件的保存、识别方式与平台级容量限制。</p>
                </div>
                <Switch checked={settings.recognitionEnabled} onChange={(value) => patch("recognitionEnabled", value)} checkedChildren="已启用" unCheckedChildren="已关闭" />
              </div>
              <div className="sys-section-card-body space-y-5">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">识别方式</label>
                  <OptionSegmented
                    value={settings.recognitionEngine}
                    onChange={(value) => patch("recognitionEngine", value as AttachmentRecognitionSettings["recognitionEngine"])}
                    options={[
                      { value: "local", label: "简单识别" },
                      { value: "mineru", label: "复杂识别（MinerU）" },
                    ]}
                  />
                  <div className="sys-field-hint">
                    简单识别由服务端直接解析常见文档；复杂识别会把白名单内的所有附件交给 MinerU，不执行本地预解析或失败回退。
                  </div>
                </div>
                <div className="sys-field-row">
                  <NumberField label="单文件上限（MB）" value={settings.maxFileSizeMb} min={1} max={200} onChange={(value) => patch("maxFileSizeMb", value)} />
                  <NumberField label="单字段文件数" value={settings.maxFilesPerField} min={1} max={20} onChange={(value) => patch("maxFilesPerField", value)} />
                </div>
                <div className="sys-field-row">
                  <NumberField label="识别正文字符上限" value={settings.maxExtractedChars} min={1000} max={2_000_000} onChange={(value) => patch("maxExtractedChars", value)} />
                  <div className="sys-field">
                    <label className="sys-field-label">文件默认保存时间</label>
                    <OptionSegmented
                      value={settings.retentionPolicy}
                      onChange={(value) => patch("retentionPolicy", value as AttachmentRecognitionSettings["retentionPolicy"])}
                      options={[{ value: "permanent", label: "永久" }, { value: "days", label: "按天保存" }]}
                    />
                    {settings.retentionPolicy === "days" ? (
                      <div className="mt-3">
                        <div className="attachment-suffix-input"><input className="sys-field-input" type="number" min={1} max={3650} value={settings.retentionDays} onChange={(event) => patch("retentionDays", numberValue(event.target.value, 30, 1, 3650))} /><span>天</span></div>
                      </div>
                    ) : (
                      <div className="sys-field-hint">默认永久保存原文件和识别结果，不设置到期时间。</div>
                    )}
                  </div>
                </div>
              </div>
            </section>

            {settings.recognitionEngine === "mineru" ? (
              <section className="sys-section-card">
                <div className="sys-section-card-header">
                  <div><h3><ServerCog size={17} className="attachment-section-icon" /> MinerU 配置</h3><p>扩展名白名单可自定义；列表内文件全部走复杂识别。</p></div>
                </div>
                <div className="sys-section-card-body space-y-5">
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">服务地址</label>
                    <div className="sys-field-input-wrap"><ServerCog size={16} className="sys-field-prefix" /><input className="sys-field-input" name="mineru-service-endpoint" autoComplete="off" spellCheck={false} value={settings.mineruEndpoint ?? ""} placeholder="http://mineru.example.internal" onChange={(event) => patch("mineruEndpoint", event.target.value)} /></div>
                  </div>
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">支持扩展名</label>
                    <Select
                      className="agent-admin-select attachment-extension-select w-full"
                      classNames={selectClassNames}
                      mode="tags"
                      open={false}
                      value={extensions}
                      tokenSeparators={[",", "，", " ", "\n"]}
                      placeholder="输入扩展名后按回车，例如 pdf"
                      suffixIcon={null}
                      onChange={(values) => setExtensionValues(parseExtensions(values.join(",")))}
                    />
                    <div className="sys-field-hint">输入后按回车，或粘贴逗号分隔的列表；不写点号。当前共 {extensions.length} 项。</div>
                  </div>
                  <div className="sys-field">
                    <label className="sys-field-label">API Key</label>
                    <div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix" /><input className="sys-field-input" name="mineru-api-credential" autoComplete="new-password" type="password" value={apiKey} placeholder={settings.mineruApiKeyConfigured ? "已配置，留空保持不变" : "按服务要求选填"} onChange={(event) => setApiKey(event.target.value)} /></div>
                    {settings.mineruApiKeyConfigured ? <Checkbox checked={clearApiKey} onChange={(event) => setClearApiKey(event.target.checked)}>清除已保存的 API Key</Checkbox> : null}
                  </div>
                  <div className="sys-field-row">
                    <div className="sys-field">
                      <label className="sys-field-label">Backend</label>
                      <Select
                        className="agent-admin-select w-full"
                        classNames={selectClassNames}
                        value={settings.mineruBackend}
                        options={BACKEND_OPTIONS}
                        suffixIcon={<ChevronDown size={16} />}
                        onChange={(value) => patch("mineruBackend", value)}
                      />
                    </div>
                    <TextField label="语言" value={settings.mineruLanguage} onChange={(value) => patch("mineruLanguage", value)} />
                  </div>
                  <div className="sys-field-row">
                    <div className="sys-field">
                      <label className="sys-field-label">解析方式</label>
                      <OptionSegmented
                        value={settings.mineruParseMethod}
                        onChange={(value) => patch("mineruParseMethod", value as AttachmentRecognitionSettings["mineruParseMethod"])}
                        options={[{ value: "auto", label: "自动识别" }, { value: "txt", label: "文本解析" }, { value: "ocr", label: "OCR 识别" }]}
                      />
                    </div>
                    <div className="sys-field"><label className="sys-field-label">识别能力</label><div className="flex gap-5"><Checkbox checked={settings.mineruEnableTable} onChange={(event) => patch("mineruEnableTable", event.target.checked)}>表格</Checkbox><Checkbox checked={settings.mineruEnableFormula} onChange={(event) => patch("mineruEnableFormula", event.target.checked)}>公式</Checkbox></div></div>
                  </div>
                  <div className="sys-field-row">
                    <NumberField label="连接超时（秒）" value={settings.mineruConnectTimeoutSeconds} min={1} max={120} onChange={(value) => patch("mineruConnectTimeoutSeconds", value)} />
                    <NumberField label="读取超时（秒）" value={settings.mineruReadTimeoutSeconds} min={10} max={3600} onChange={(value) => patch("mineruReadTimeoutSeconds", value)} />
                  </div>
                </div>
              </section>
            ) : null}

            <div className="flex justify-end gap-2">
              {settings.recognitionEngine === "mineru" ? <button type="button" className="sys-btn sys-btn--default" disabled={testing} title="测试当前页面中尚未保存的 MinerU 地址、密钥和超时配置" onClick={() => void testConnection()}><PlayCircle size={15} />{testing ? "测试中" : "测试当前配置"}</button> : null}
              <button type="button" className="sys-btn sys-btn--primary" disabled={saving} onClick={() => void save()}><Save size={15} />{saving ? "保存中" : "保存配置"}</button>
            </div>
          </div>
        ) : null}
      </Spin>
    </div>
  );
}

function NumberField({ label, value, min, max, onChange }: { label: string; value: number; min: number; max: number; onChange: (value: number) => void }) {
  return <div className="sys-field"><label className="sys-field-label">{label}</label><input className="sys-field-input" type="number" min={min} max={max} value={value} onChange={(event) => onChange(numberValue(event.target.value, min, min, max))} /></div>;
}

function TextField({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return <div className="sys-field"><label className="sys-field-label">{label}</label><input className="sys-field-input" value={value} onChange={(event) => onChange(event.target.value)} /></div>;
}

/** 表单内选项切换：复用全局 Segmented 视觉与滑块动效，避免自定义描边按钮组显得突兀。 */
function OptionSegmented({ value, options, onChange }: { value: string; options: Array<{ value: string; label: string }>; onChange: (value: string) => void }) {
  return (
    <Segmented
      block
      value={value}
      options={options}
      onChange={(next) => onChange(String(next))}
      className="login-portal-segmented form-option-segmented"
    />
  );
}

function numberValue(value: string, fallback: number, min: number, max: number): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback;
}
