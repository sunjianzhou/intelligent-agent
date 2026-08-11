package com.intelligent.agent.web.feishu;

import com.intelligent.agent.web.service.FeishuOAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 飞书 OAuth Controller（本地 {@link FeishuOAuthService}）。
 *
 * callback 端点无 JWT 校验（飞书服务器重定向，路径以 /feishu/ 开头已在白名单中）；
 * authorize / status 端点有正常 JWT 校验（前端调用）。
 */
@Slf4j
@RestController
@RequestMapping("/feishu/oauth")
public class FeishuOAuthController {

    @Autowired
    private FeishuOAuthService feishuOAuthService;

    /** 无 JWT：飞书服务器将用户浏览器重定向至此，由本地服务换取 user_access_token。 */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oauthCallback(HttpServletRequest request) {
        String code = request.getParameter("code");
        String state = request.getParameter("state");
        String error = request.getParameter("error");
        if (error != null) {
            return ResponseEntity.ok(DENIED_HTML);
        }
        return ResponseEntity.ok(feishuOAuthService.callback(code, state));
    }

    /** 有 JWT：前端查询用户授权状态。 */
    @GetMapping("/status")
    public ResponseEntity<?> oauthStatus(@RequestParam("open_id") String openId) {
        return ResponseEntity.ok(feishuOAuthService.status(openId));
    }

    /** 有 JWT：前端获取授权链接。 */
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(@RequestParam("open_id") String openId) {
        return ResponseEntity.ok(feishuOAuthService.authorize(openId));
    }

    private static final String DENIED_HTML =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>授权被拒绝</title></head>"
                    + "<body><h2>&#x274C; 拒绝授权</h2>"
                    + "<p>你拒绝了飞书授权。如需重新授权，请向 agent 发送\"给我飞书日历授权链接\"。</p>"
                    + "</body></html>";
}
