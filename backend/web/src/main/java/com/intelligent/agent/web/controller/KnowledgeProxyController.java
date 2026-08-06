package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识文件入库代理端点（转发到 Python Agent /api/knowledge/*）。
 */
@Slf4j
@RestController
public class KnowledgeProxyController extends AbstractProxyController {

    @PostMapping(value = "/api/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.postMultipart("/api/knowledge/upload", file, description, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("文件上传转发失败: {}", file.getOriginalFilename(), e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "文件上传失败，请重试");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/api/knowledge/files")
    public ResponseEntity<Map<String, Object>> listFiles(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("files", Collections.emptyList());
        return proxyGet("/api/knowledge/files", req, fallback);
    }

    @DeleteMapping("/api/knowledge/files/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String fileId, HttpServletRequest req) {
        return proxyDelete("/api/knowledge/files/" + fileId, req);
    }
}
