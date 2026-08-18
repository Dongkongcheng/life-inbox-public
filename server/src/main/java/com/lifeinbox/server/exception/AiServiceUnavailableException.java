package com.lifeinbox.server.exception;

/**
 * 隔离 Python 通信失败与 Inbox 业务异常，确保 AI 故障不会进入 Capture 流程。
 */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(String message) {
        super(message);
    }

    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
