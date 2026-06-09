package com.intelligent.agent.web.dto.role;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 角色完整配置 DTO（根模型）。
 * 对应 Python RoleConfig Pydantic 模型，用于 REST API 接收/返回。
 */
@Data
public class RoleConfigDto implements Serializable {

    private String roleId;
    private CoreIdentityDto coreIdentity;
    private UserProfileDto userProfile;
    private RoleMemoryConfigDto roleMemory;
    private RoleCardDto roleCard;
    private KnowledgeJournalConfigDto knowledgeJournal;
    private String createdAt;
    private String updatedAt;

    // ── 内部 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class CoreIdentityDto implements Serializable {
        private List<String> personality;
        private List<String> principles;
        /** 绝对底线：优先级最高，序列化时保持顺序 */
        private List<String> redlines;
        private String languageStyle;
    }

    @Data
    public static class UserProfileDto implements Serializable {
        private String nickname;
        private String background;
        /** 沟通偏好，如 {"tone":"casual","detail":"brief"} */
        private java.util.Map<String, String> preferences;
        private String relationship;
        private List<String> disclosedInfo;
    }

    @Data
    public static class CommitmentDto implements Serializable {
        private String content;
        private String timestamp;
        /** active | fulfilled | cancelled */
        private String status;
    }

    @Data
    public static class RetrievalRuleDto implements Serializable {
        private double similarityThreshold;
        private int topK;
        private double recencyWeight;
    }

    @Data
    public static class RoleMemoryConfigDto implements Serializable {
        private List<CommitmentDto> commitments;
        private RetrievalRuleDto retrievalRule;
        /** 短期记忆 deque 最大条数（Python 运行时参数，Java 仅存储配置值） */
        private int shortTermSize;
    }

    @Data
    public static class RoleCardDto implements Serializable {
        private String name;
        private String avatarUrl;
        private String signature;
        private List<String> tags;
    }

    @Data
    public static class JournalEntryDto implements Serializable {
        private String date;
        private String content;
    }

    @Data
    public static class KnowledgeJournalConfigDto implements Serializable {
        /** 最近日记缓存（JSON 中保留最新 10 条，完整数据在 ChromaDB） */
        private List<JournalEntryDto> recentJournalEntries;
        private String retrievalStrategy;
        private List<String> knowledgeSources;
    }
}
