package com.intelligent.agent.web.ai.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多 system 消息合并：Ollama 模板只渲染第一条 system（{@code .System}），
 * 其余 system 消息在 .Messages 循环里没有分支、会被静默丢弃。
 */
class ChatMessageTest {

    @Test
    void mergesAllSystemMessagesIntoSingleFirstSystem() {
        List<ChatMessage> merged = ChatMessage.mergeSystemMessages(List.of(
                ChatMessage.system("base prompt"),
                ChatMessage.system("[SEMANTIC MEMORY]\n- 晨会周五下午三点"),
                ChatMessage.user("我的晨会是什么时候？"),
                ChatMessage.assistant("稍等"),
                ChatMessage.system("收尾指令"),
                ChatMessage.user("继续")));

        assertThat(merged).hasSize(4);
        assertThat(merged.get(0).role()).isEqualTo("system");
        assertThat(merged.get(0).content())
                .startsWith("base prompt")
                .contains("[SEMANTIC MEMORY]")
                .contains("晨会周五下午三点")
                .contains("收尾指令");
        assertThat(merged.subList(1, merged.size()))
                .extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "user");
    }

    @Test
    void keepsFirstSystemPositionWhenItIsNotIndexZero() {
        List<ChatMessage> merged = ChatMessage.mergeSystemMessages(List.of(
                ChatMessage.user("先说话"),
                ChatMessage.system("a"),
                ChatMessage.system("b")));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).role()).isEqualTo("user");
        assertThat(merged.get(1).content()).isEqualTo("a\n\nb");
    }

    @Test
    void leavesMessagesUntouchedWhenAtMostOneSystem() {
        List<ChatMessage> single = List.of(
                ChatMessage.system("only"), ChatMessage.user("hi"));
        assertThat(ChatMessage.mergeSystemMessages(single)).isSameAs(single);

        List<ChatMessage> none = List.of(ChatMessage.user("hi"));
        assertThat(ChatMessage.mergeSystemMessages(none)).isSameAs(none);
        assertThat(ChatMessage.mergeSystemMessages(null)).isNull();
    }
}
