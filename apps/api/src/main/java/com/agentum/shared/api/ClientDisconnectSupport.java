package com.agentum.shared.api;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * SSE / 流式响应在客户端主动断开时常见 Broken pipe，不应按系统异常处理。
 */
public final class ClientDisconnectSupport {

    private ClientDisconnectSupport() {
    }

    public static boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException || current instanceof ClientAbortException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("broken pipe")
                    || normalized.contains("connection reset")
                    || normalized.contains("connection abort")
                    || normalized.contains("already completed")
                    || normalized.contains("async request not usable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
