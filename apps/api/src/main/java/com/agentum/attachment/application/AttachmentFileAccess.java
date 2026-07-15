package com.agentum.attachment.application;

/** 受保护附件文件响应，由控制器统一补充防嗅探和缓存头。 */
public record AttachmentFileAccess(byte[] bytes, String fileName, String contentType) {
}
