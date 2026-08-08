package com.intelligent.agent.web.domain;

/** 领域资源不存在（控制器映射为 HTTP 404）。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
