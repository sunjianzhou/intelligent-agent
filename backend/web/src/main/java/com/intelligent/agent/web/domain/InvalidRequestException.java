package com.intelligent.agent.web.domain;

/** 领域请求无效（控制器映射为 HTTP 400）。 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
