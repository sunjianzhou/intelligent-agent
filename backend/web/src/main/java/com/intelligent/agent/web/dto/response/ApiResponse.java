package com.intelligent.agent.web.dto.response;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Data
public class ApiResponse<T extends Serializable> {
    private boolean success;
    private String message;
    private T data;
    private LocalDateTime timestamp;
    private String requestId;

    public static <T extends Serializable> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(true);
        response.setMessage("操作成功");
        response.setData(data);
        response.setTimestamp(LocalDateTime.now());
        return response;
    }

    public static <T extends Serializable> ApiResponse<T> success(String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(true);
        response.setMessage(message);
        response.setData(data);
        response.setTimestamp(LocalDateTime.now());
        return response;
    }

    public static <T extends Serializable> ApiResponse<T> error(String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setMessage(message);
        response.setTimestamp(LocalDateTime.now());
        return response;
    }

    public static <T extends Serializable> ApiResponse<T> error(String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setData(data);
        response.setMessage(message);
        response.setTimestamp(LocalDateTime.now());
        return response;
    }
}
