package com.intelligent.agent.client.chat;

import com.intelligent.agent.client.auth.TokenStore;
import com.intelligent.agent.client.http.BackendClient;
import com.intelligent.agent.client.session.SessionStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * agent-cli chat：单轮聊天（流式 SSE 渲染 + 非流式回退），
 * 自动写入本地 JSON 会话文件（Plan 3 / Task 2）。
 * 省略 message 进入 REPL（Task 3 实现）。
 */
@Command(name = "chat", description = "Chat with the agent (streaming)")
public class ChatCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Message to send (omit for REPL)")
    private String message;

    @Option(names = "--url", defaultValue = "http://localhost:8080",
            description = "Backend base URL (default: ${DEFAULT-VALUE})")
    private String url;

    @Option(names = "--user", defaultValue = "cli-user", description = "User ID")
    private String user;

    @Option(names = "--model", description = "Model name")
    private String model;

    @Option(names = "--persona", description = "Persona name")
    private String persona;

    @Option(names = "--no-stream", description = "Disable streaming")
    private boolean noStream;

    @Option(names = "--no-tools", description = "Disable tool use")
    private boolean noTools;

    @Option(names = "--no-memory", description = "Disable memory context")
    private boolean noMemory;

    @Option(names = "--no-save", description = "Do not save session")
    private boolean noSave;

    @Option(names = "--data-dir", defaultValue = "./datas", description = "Session data dir")
    private Path dataDir;

    @Option(names = "--token-file", description = "Override token file path")
    private Path tokenFile;

    @Override
    public Integer call() throws Exception {
        TokenStore tokenStore = tokenFile != null ? new TokenStore(tokenFile) : TokenStore.defaultStore();
        String token = tokenStore.load();
        if (token == null || token.isBlank()) {
            System.err.println("未找到登录 token，请先运行: agent-cli login --username <user> --password <pw>");
            return 2;
        }
        if (message == null || message.isBlank()) {
            java.util.List<String> replArgs = new java.util.ArrayList<>();
            replArgs.add("--url");
            replArgs.add(url);
            replArgs.add("--user");
            replArgs.add(user);
            replArgs.add("--data-dir");
            replArgs.add(dataDir.toString());
            if (noTools) replArgs.add("--no-tools");
            if (noMemory) replArgs.add("--no-memory");
            if (tokenFile != null) {
                replArgs.add("--token-file");
                replArgs.add(tokenFile.toString());
            }
            return new CommandLine(new ReplCommand()).execute(replArgs.toArray(new String[0]));
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("use_tools", !noTools);
        options.put("use_memory", !noMemory);
        if (model != null) options.put("model", model);
        if (persona != null) options.put("persona", persona);

        BackendClient client = new BackendClient(url, token);
        SessionStore sessionStore = new SessionStore(dataDir);
        Map<String, Object> session = sessionStore.newSession(user);
        sessionStore.append(session, "user", message);

        String answer;
        if (noStream) {
            answer = client.complete(message, options);
            System.out.println(answer);
        } else {
            StringBuilder printed = new StringBuilder();
            answer = client.stream(message, options, event -> {
                if ("token".equals(event.type())) {
                    String tokenText = unquote(event.data());
                    printed.append(tokenText);
                    System.out.print(tokenText);
                    System.out.flush();
                } else if ("error".equals(event.type())) {
                    System.err.println("\n[error] " + unquote(event.data()));
                }
            });
            System.out.println();
        }

        sessionStore.append(session, "assistant", answer);
        if (!noSave) {
            Path saved = sessionStore.save(session);
            System.out.println("[session saved] " + saved);
        }
        return 0;
    }

    private static String unquote(String jsonData) {
        if (jsonData == null || jsonData.isEmpty() || "{}".equals(jsonData)) {
            return "";
        }
        String s = jsonData;
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
