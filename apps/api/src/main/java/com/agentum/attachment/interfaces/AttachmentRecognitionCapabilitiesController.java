package com.agentum.attachment.interfaces;

import com.agentum.attachment.application.AttachmentRecognitionSettingsService;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向已登录用户公开当前附件识别能力，不包含 MinerU 地址、密钥状态等系统敏感配置。
 * 流程设计和运行上传共用该接口，避免前端各自硬编码扩展名名单。
 */
@RestController
@RequestMapping("/api/attachment-recognition/capabilities")
public class AttachmentRecognitionCapabilitiesController {

    private final AttachmentRecognitionSettingsService settingsService;

    public AttachmentRecognitionCapabilitiesController(AttachmentRecognitionSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ApiResponse<AttachmentRecognitionApi.Capabilities> get(HttpServletRequest request) {
        return ApiResponse.success(settingsService.getCapabilities(), RequestIds.current(request));
    }
}
