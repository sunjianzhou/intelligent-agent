package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.PayloadTooLargeException;
import com.intelligent.agent.web.domain.knowledge.KnowledgeService;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识文件入库端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link KnowledgeService}；</li>
 *   <li>python 运行时：转发到 Python Agent /api/knowledge/*。</li>
 * </ul>
 */
@Slf4j
@RestController
public class KnowledgeProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;
    private final String runtimeMode;

    public KnowledgeProxyController(PythonProxyService proxy,
                                    ObjectMapper objectMapper,
                                    KnowledgeService knowledgeService,
                                    @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.runtimeMode = runtimeMode;
    }

    @PostMapping(value = "/api/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            HttpServletRequest req) throws Exception {
        if (localRuntime()) {
            try {
                return ResponseEntity.ok(knowledgeService.upload(
                        userId(req), file.getOriginalFilename(), file.getBytes(), description));
            } catch (PayloadTooLargeException e) {
                return ResponseEntity.status(413).body(error(e.getMessage()));
            } catch (NotFoundException e) {
                return ResponseEntity.status(404).body(error(e.getMessage()));
            } catch (InvalidRequestException e) {
                return ResponseEntity.status(400).body(error(e.getMessage()));
            }
        }
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.postMultipart(
                    "/api/knowledge/upload", file, description, userId);
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
        if (localRuntime()) {
            return ResponseEntity.ok(knowledgeService.listFiles(userId(req)));
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("files", Collections.emptyList());
        return proxyGet("/api/knowledge/files", req, fallback);
    }

    @DeleteMapping("/api/knowledge/files/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String fileId, HttpServletRequest req) {
        if (localRuntime()) {
            try {
                return ResponseEntity.ok(knowledgeService.deleteFile(userId(req), fileId));
            } catch (NotFoundException e) {
                return ResponseEntity.status(404).body(error(e.getMessage()));
            }
        }
        return proxyDelete("/api/knowledge/files/" + fileId, req);
    }

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private String userId(HttpServletRequest req) {
        if (proxy != null) {
            String userId = proxy.extractUserIdFromRequest(req);
            if (userId != null) {
                return userId;
            }
        }
        return "default";
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req,
                                                         Map<String, Object> fallback) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("GET {} 失败", path, e);
        }
        return ResponseEntity.ok(new HashMap<>(fallback));
    }

    private ResponseEntity<Map<String, Object>> proxyDelete(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
            if (res.getStatusCode().value() == 404) {
                return ResponseEntity.status(404).body(error("文件不存在"));
            }
        } catch (Exception e) {
            log.error("DELETE {} 失败", path, e);
        }
        return ResponseEntity.ok(error("删除文件失败"));
    }
}
