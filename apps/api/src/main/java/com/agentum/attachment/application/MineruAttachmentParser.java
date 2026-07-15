package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** MinerU 适配器只负责协议转换；文件白名单、权限和失败策略由 Agentum 自身治理。 */
@Component
public class MineruAttachmentParser {

    private final ObjectMapper objectMapper;
    private final AttachmentRecognitionSettingsService settingsService;

    public MineruAttachmentParser(ObjectMapper objectMapper, AttachmentRecognitionSettingsService settingsService) {
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public String parse(AttachmentRecognitionSettingEntity settings, String fileName, byte[] bytes) {
        String endpoint = settings.getMineruEndpoint() == null ? "" : settings.getMineruEndpoint().replaceAll("/+$", "");
        if (endpoint.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_ENDPOINT_REQUIRED", "MinerU 服务地址未配置");
        }
        String boundary = "----AgentumAttachment" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, settings, fileName, bytes);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.getMineruConnectTimeoutSeconds())).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/file_parse"))
            .timeout(Duration.ofSeconds(settings.getMineruReadTimeoutSeconds()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        String apiKey = settingsService.decryptMineruApiKey(settings);
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_HTTP_FAILED", "MinerU 返回 HTTP " + response.statusCode());
            }
            JsonNode payload = objectMapper.readTree(response.body());
            String markdown = findMarkdown(payload.path("results"));
            if (markdown.isBlank() && "completed".equals(payload.path("status").asText()) && !payload.path("result_url").asText().isBlank()) {
                markdown = fetchResult(client, payload.path("result_url").asText(), apiKey, settings.getMineruReadTimeoutSeconds());
            }
            if (markdown.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_RESULT_EMPTY", "MinerU 响应中没有可用解析正文");
            }
            return markdown;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_CALL_FAILED", "调用 MinerU 解析失败，请检查服务状态");
        }
    }

    private String fetchResult(HttpClient client, String resultUrl, String apiKey, int timeoutSeconds) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(resultUrl)).timeout(Duration.ofSeconds(timeoutSeconds)).GET();
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
        return findMarkdown(objectMapper.readTree(response.body()).path("results"));
    }

    private static String findMarkdown(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isObject()) {
            for (String key : new String[]{"md_content", "markdown", "content"}) {
                JsonNode value = node.get(key);
                if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String value = findMarkdown(fields.next().getValue());
                if (!value.isBlank()) return value;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String value = findMarkdown(item);
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private static byte[] multipart(String boundary, AttachmentRecognitionSettingEntity settings, String fileName, byte[] bytes) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_md", "true");
        fields.put("return_images", "false");
        fields.put("table_enable", String.valueOf(settings.isMineruEnableTable()));
        fields.put("formula_enable", String.valueOf(settings.isMineruEnableFormula()));
        fields.put("parse_method", settings.getMineruParseMethod());
        fields.put("backend", settings.getMineruBackend());
        fields.put("lang_list", settings.getMineruLanguage());
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + safeFileName(fileName) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_MULTIPART_BUILD_FAILED", "构建 MinerU 文件请求失败");
        }
    }

    private static String safeFileName(String value) {
        return (value == null ? "attachment.bin" : value).replace("\"", "-").replace("\r", "-").replace("\n", "-");
    }
}
