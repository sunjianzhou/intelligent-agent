package com.intelligent.agent.client.conversation;

import com.intelligent.agent.client.auth.TokenStore;
import com.intelligent.agent.client.http.BackendClient;
import com.intelligent.agent.client.http.RetractResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 消息撤回命令：agent-cli retract <sessionId> <id1,id2,...>。
 */
@Command(name = "retract", description = "Retract messages from a session")
public class RetractCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Session id")
    private String sessionId;

    @Parameters(index = "1", description = "Comma-separated message ids")
    private String messageIds;

    @Option(names = "--url", defaultValue = "http://localhost:8080")
    private String url;

    @Option(names = "--token-file", description = "Token file path")
    private java.nio.file.Path tokenFile;

    @Override
    public Integer call() throws Exception {
        TokenStore store = tokenFile != null ? new TokenStore(tokenFile) : TokenStore.defaultStore();
        String token = store.load();
        if (token == null || token.isBlank()) {
            System.err.println("未找到登录 token，请先运行 agent-cli login");
            return 2;
        }
        List<String> ids = Arrays.stream(messageIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        RetractResult result = new BackendClient(url, token).retract(sessionId, ids);
        System.out.println("retracted=" + result.deleted() + " / requested=" + result.requested());
        return result.success() ? 0 : 1;
    }
}
