package com.agentum.auth.interfaces;

// 登录响应只返回前端保存会话所需的 token 和用户上下文；后端日志禁止输出 token 原文。
public record LoginResponse(String token, AuthUserResponse user) {
}
