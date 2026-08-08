package com.intelligent.agent.web.infrastructure.migration;

/** 迁移校验失败：记录数或哈希与清单不一致。 */
public class MigrationValidationException extends RuntimeException {
    public MigrationValidationException(String message) {
        super(message);
    }
}
