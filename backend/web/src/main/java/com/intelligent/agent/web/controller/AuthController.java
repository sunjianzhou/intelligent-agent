package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.config.AuthProperties;
import com.intelligent.agent.web.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/11
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthProperties authProperties;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        boolean valid = authProperties.getUsers().stream().anyMatch(u ->
                u.getUsername().equals(username) && u.getPassword().equals(password)
        );

        Map<String, Object> result = new HashMap<>();
        if (!valid) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");

            return ResponseEntity.status(401).body(result);
        }

        String token = jwtUtil.generateToken(username);
        result.put("token", token);
        result.put("success", true);
        result.put("username", username);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        // 前端清 token 即可，无需服务端操作
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }
}
