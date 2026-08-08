package com.intelligent.agent.client;

import com.intelligent.agent.client.auth.LoginCommand;
import com.intelligent.agent.client.chat.ReplCommand;
import com.intelligent.agent.client.conversation.RetractCommand;
import com.intelligent.agent.client.model.ModelCommand;
import com.intelligent.agent.client.role.PersonaCommand;
import picocli.CommandLine;

/**
 * Intelligent Agent Java CLI 入口（Plan 3）。
 * <p>
 * 子命令：
 *   login — 登录并保存 scoped CLI token（绝不保存 JWT_SECRET）
 *   chat  — 聊天（Task 2 起支持流式；Task 3 补齐 REPL 与 ! 命令）
 */
@CommandLine.Command(
        name = "agent-cli",
        description = "Intelligent Agent CLI client",
        mixinStandardHelpOptions = true,
        subcommands = {
                LoginCommand.class,
                com.intelligent.agent.client.chat.ChatCommand.class,
                ReplCommand.class,
                ModelCommand.class,
                PersonaCommand.class,
                RetractCommand.class
        }
)
public class Main implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
