package com.intelligent.agent.web.feishu;

import com.intelligent.agent.web.controller.AbstractProxyController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 飞书 OAuth 代理 Controller。
 *
 * callback 端点无 JWT 校验（飞书服务器重定向，没有 JWT，路径以 /feishu/ 开头已在白名单中）；
 * authorize / status 端点有正常 JWT 校验（前端调用）。
 */
@Slf4j
@RestController
@RequestMapping("/feishu/oauth")
public class FeishuOAuthController extends AbstractProxyController {

    /**
     * 无 JWT：飞书服务器将用户浏览器重定向至此，不携带 JWT。
     * 原样透传 code+state 给 Python，Python 返回 HTML，直接透传给浏览器。
     * /feishu/ 前缀已在 JwtAuthFilter 白名单中，无需额外配置。
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oauthCallback(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = "/api/feishu/oauth/callback" + (query != null ? "?" + query : "");
        log.info("飞书 OAuth 回调透传: {}", path);
        return proxyGetRaw(path);
    }

    /** 有 JWT：前端查询用户授权状态。 */
    @GetMapping("/status")
    public ResponseEntity<?> oauthStatus(
            @RequestParam("open_id") String openId,
            HttpServletRequest req) {
        return proxyGet("/api/feishu/oauth/status?open_id=" + openId, req);
    }

    /** 有 JWT：前端获取授权链接。 */
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(
            @RequestParam("open_id") String openId,
            HttpServletRequest req) {
        return proxyGet("/api/feishu/oauth/authorize?open_id=" + openId, req);
    }
}
