package com.intelligent.agent.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 将所有 Vue Router 前端路由转发到 index.html，支持浏览器直接访问任意路径。
 * API 和 WebSocket 路径由各自 Controller 优先匹配，不会命中此处。
 */
@Controller
public class SpaController {

    @GetMapping({
        "/", "/chat", "/tools", "/skills",
        "/tasks", "/memory", "/system", "/stats", "/login"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
