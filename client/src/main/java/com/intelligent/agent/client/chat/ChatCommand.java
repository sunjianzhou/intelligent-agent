package com.intelligent.agent.client.chat;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * agent-cli chat：流式聊天（Plan 3 Task 2 实现 BackendClient + SSE 渲染；
 * Task 3 补齐 REPL 与 ! 命令）。
 */
@Command(name = "chat", description = "Chat with the agent (streaming)")
public class ChatCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("chat 命令将在 Task 2 中实现（BackendClient + SSE 流式渲染）。");
        return 0;
    }
}
