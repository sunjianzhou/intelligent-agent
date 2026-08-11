package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.PayloadTooLargeException;
import com.intelligent.agent.web.domain.knowledge.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识文件入库端点（本地 {@link KnowledgeService}）。
 */
@Slf4j
@RestController
public class KnowledgeProxyController {

    private final KnowledgeService knowledgeService;

    public KnowledgeProxyController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping(value = "/api/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            HttpServletRequest req) throws Exception {
        try {
            return ResponseEntity.ok(knowledgeService.upload(
                    UserContext.userId(req), file.getOriginalFilename(), file.getBytes(), description));
        } catch (PayloadTooLargeException e) {
            return ResponseEntity.status(413).body(error(e.getMessage()));
        } catch (NotFoundException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(error(e.getMessage()));
        }
    }

    @GetMapping("/api/knowledge/files")
    public ResponseEntity<Map<String, Object>> listFiles(HttpServletRequest req) {
        return ResponseEntity.ok(knowledgeService.listFiles(UserContext.userId(req)));
    }

    @DeleteMapping("/api/knowledge/files/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String fileId, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(knowledgeService.deleteFile(UserContext.userId(req), fileId));
        } catch (NotFoundException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
