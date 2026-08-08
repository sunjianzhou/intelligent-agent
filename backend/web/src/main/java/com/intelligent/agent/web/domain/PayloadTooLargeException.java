package com.intelligent.agent.web.domain;

/** 请求体过大（控制器映射为 HTTP 413）。 */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
