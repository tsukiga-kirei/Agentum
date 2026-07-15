import { useEffect, useMemo, useState } from "react";
import { Alert, Checkbox, InputNumber, Radio, Spin, Switch, message } from "antd";
import { FileSearch, KeyRound, PlayCircle, Save, ServerCog } from "lucide-react";
import { AgentumApiError, systemApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AttachmentRecognitionSettings, UpdateAttachmentRecognitionSettingsRequest } from "../../types/system";

const DEFAULT_COMPLEX_EXTENSIONS = "pdf,png,jpg,jpeg,bmp,gif,tiff,webp,docx,xlsx,txt";

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
  const [extensionText, setExtensionText] = useState(DEFAULT_COMPLEX_EXTENSIONS);

  useEffect(() => {
    if (!token) return;
    setLoading(true);
    systemApi.getAttachmentRecognitionSettings(token)
      .then((result) => {
        setSettings(result);
        setExtensionText(result.mineruSupportedExtensions.join(","));
      })
      .catch((error) => messageApi.error(error instanceof AgentumApiError ? error.message : "附件识别配置加载失败"))
      .finally(() => setLoading(false));
  }, [messageApi, token]);

  const extensions = useMemo(() => parseExtensions(extensionText), [extensionText]);

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
      setExtensionText(result.mineruSupportedExtensions.join(","));
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
    <div className="sys-fade-in">
      {contextHolder}
      <Spin spinning={loading}>
        {settings ? (
          <div className="space-y-4">
            <section className="sys-section-card">
              <div className="sys-section-card-header">
                <div>
                  <h3><FileSearch size={17} /> 附件识别</h3>
                  <p>配置输入节点附件的保存、识别方式与平台级容量限制。</p>
                </div>
                <Switch checked={settings.recognitionEnabled} onChange={(value) => patch("recognitionEnabled", value)} checkedChildren="已启用" unCheckedChildren="已关闭" />
              </div>
              <div className="sys-section-card-body space-y-5">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">识别方式</label>
                  <Radio.Group
                    value={settings.recognitionEngine}
                    onChange={(event) => patch("recognitionEngine", event.target.value)}
                    optionType="button"
                    buttonStyle="solid"
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
                    <Radio.Group
                      value={settings.retentionPolicy}
                      onChange={(event) => patch("retentionPolicy", event.target.value)}
                      options={[{ value: "permanent", label: "永久" }, { value: "days", label: "按天保存" }]}
                    />
                    {settings.retentionPolicy === "days" ? (
                      <div className="mt-3">
                        <InputNumber className="w-full" min={1} max={3650} addonAfter="天" value={settings.retentionDays} onChange={(value) => patch("retentionDays", value ?? 30)} />
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
                  <div><h3><ServerCog size={17} /> MinerU 配置</h3><p>扩展名白名单可自定义；列表内文件全部走复杂识别。</p></div>
                </div>
                <div className="sys-section-card-body space-y-5">
                  <Alert type="info" showIcon message="doc、xls 只有加入白名单后才会发送给 MinerU；服务端不替 MinerU 承诺格式支持，供应商拒绝时会明确标记识别失败。" />
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">服务地址</label>
                    <div className="sys-field-input-wrap"><ServerCog size={16} className="sys-field-prefix" /><input className="sys-field-input" value={settings.mineruEndpoint ?? ""} placeholder="http://mineru.example.internal" onChange={(event) => patch("mineruEndpoint", event.target.value)} /></div>
                  </div>
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">支持扩展名</label>
                    <textarea className="sys-input w-full min-h-24 p-3" value={extensionText} onChange={(event) => setExtensionText(event.target.value)} placeholder={DEFAULT_COMPLEX_EXTENSIONS} />
                    <div className="sys-field-hint">使用逗号、空格或换行分隔，不写点号。当前共 {extensions.length} 项。</div>
                  </div>
                  <div className="sys-field">
                    <label className="sys-field-label">API Key</label>
                    <div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix" /><input className="sys-field-input" type="password" value={apiKey} placeholder={settings.mineruApiKeyConfigured ? "已配置，留空保持不变" : "按服务要求选填"} onChange={(event) => setApiKey(event.target.value)} /></div>
                    {settings.mineruApiKeyConfigured ? <Checkbox checked={clearApiKey} onChange={(event) => setClearApiKey(event.target.checked)}>清除已保存的 API Key</Checkbox> : null}
                  </div>
                  <div className="sys-field-row">
                    <TextField label="Backend" value={settings.mineruBackend} onChange={(value) => patch("mineruBackend", value)} />
                    <TextField label="语言" value={settings.mineruLanguage} onChange={(value) => patch("mineruLanguage", value)} />
                  </div>
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label">解析方式</label><Radio.Group value={settings.mineruParseMethod} onChange={(event) => patch("mineruParseMethod", event.target.value)} options={[{ value: "auto", label: "auto" }, { value: "txt", label: "txt" }, { value: "ocr", label: "ocr" }]} /></div>
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
              {settings.recognitionEngine === "mineru" ? <button type="button" className="sys-btn sys-btn--default" disabled={testing} onClick={() => void testConnection()}><PlayCircle size={15} />{testing ? "测试中" : "测试连接"}</button> : null}
              <button type="button" className="sys-btn sys-btn--primary" disabled={saving} onClick={() => void save()}><Save size={15} />{saving ? "保存中" : "保存配置"}</button>
            </div>
          </div>
        ) : null}
      </Spin>
    </div>
  );
}

function NumberField({ label, value, min, max, onChange }: { label: string; value: number; min: number; max: number; onChange: (value: number) => void }) {
  return <div className="sys-field"><label className="sys-field-label">{label}</label><InputNumber className="w-full" min={min} max={max} value={value} onChange={(next) => onChange(next ?? min)} /></div>;
}

function TextField({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return <div className="sys-field"><label className="sys-field-label">{label}</label><input className="sys-field-input" value={value} onChange={(event) => onChange(event.target.value)} /></div>;
}
