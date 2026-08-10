package com.intelligent.agent.web.ai.prompt;

import java.util.Map;

/**
 * 灵魂层数据（对齐 Python SoulData v1.1）。
 *
 * @param soul         SOUL.md 灵魂核心
 * @param user         USER.md 用户画像
 * @param memory       MEMORY.md 精选记忆
 * @param identity     IDENTITY.md 身份
 * @param heartbeat    HEARTBEAT.md 自检铁规
 * @param whisper      whisper.md 私密档案（可为空）
 * @param heart        heart.md 心证铁卷（可为空）
 * @param rules        rules.md 主人铁律（可为空）
 * @param totalChars   已加载文件字符总数（可观测性）
 * @param fileSizes    {文件名: 字符数}
 */
public record SoulData(
        String soul,
        String user,
        String memory,
        String identity,
        String heartbeat,
        String whisper,
        String heart,
        String rules,
        int totalChars,
        Map<String, Integer> fileSizes) {

    public SoulData {
        soul = soul == null ? "" : soul;
        user = user == null ? "" : user;
        memory = memory == null ? "" : memory;
        identity = identity == null ? "" : identity;
        heartbeat = heartbeat == null ? "" : heartbeat;
        whisper = whisper == null ? "" : whisper;
        heart = heart == null ? "" : heart;
        rules = rules == null ? "" : rules;
        fileSizes = fileSizes == null ? Map.of() : Map.copyOf(fileSizes);
    }

    public static SoulData empty() {
        return new SoulData("", "", "", "", "", "", "", "", 0, Map.of());
    }
}
