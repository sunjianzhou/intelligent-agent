package com.intelligent.agent.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/11
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {
    private Jwt jwt = new Jwt();
    private List<User> users;

    @Data
    public static class Jwt {
        private String secret;
        private int expiryHours = 24;
    }

    @Data
    public static class User {
        private String username;
        private String password;
    }
}
