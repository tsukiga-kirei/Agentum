package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import java.util.List;

/**
 * 附件识别扩展名策略的单一事实来源，供系统配置展示、流程发布校验和运行态上传共同复用。
 * 简单识别名单由本地解析器能力决定；复杂识别名单仍由系统管理员配置。
 */
public final class AttachmentRecognitionPolicy {

    private static final List<String> LOCAL_SUPPORTED_EXTENSIONS = List.of(
        "txt", "md", "csv", "pdf", "doc", "docx", "xls", "xlsx"
    );
    private static final List<String> BLOCKED_EXTENSIONS = List.of(
        "app", "bat", "cmd", "com", "dll", "dmg", "exe", "hta", "jar", "js", "msi", "ps1", "scr", "sh", "vbs"
    );

    private AttachmentRecognitionPolicy() {
    }

    public static List<String> localSupportedExtensions() {
        return LOCAL_SUPPORTED_EXTENSIONS;
    }

    public static List<String> blockedExtensions() {
        return BLOCKED_EXTENSIONS;
    }

    public static List<String> supportedExtensions(AttachmentRecognitionSettingEntity settings) {
        return "mineru".equals(settings.getRecognitionEngine())
            ? settings.getMineruSupportedExtensions()
            : LOCAL_SUPPORTED_EXTENSIONS;
    }
}
