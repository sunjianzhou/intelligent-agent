package com.intelligent.agent.client.chat;

import com.intelligent.agent.client.auth.TokenStore;
import com.intelligent.agent.client.http.BackendClient;
import com.intelligent.agent.client.http.RetractResult;
import com.intelligent.agent.client.session.SessionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 交互式 REPL（Plan 3 / Task 3）：
 * !models / !model / !personas / !persona / !history / !retract / !sessions /
 * !clear / !exit；其余输入走流式聊天并写入本地会话。
 */
@Command(name = "repl", description = "Interactive REPL")
public class ReplCommand implements Callable<Integer> {

    @Option(names = "--url", defaultValue = "http://localhost:8080")
    private String url;

    @Option(names = "--user", defaultValue = "cli-user")
    private String user;

    @Option(names = "--no-tools", description = "Disable tool use")
    private boolean noTools;

    @Option(names = "--no-memory", description = "Disable memory context")
    private boolean noMemory;

    @Option(names = "--data-dir", defaultValue = "./datas")
    private Path dataDir;

    @Option(names = "--token-file", description = "Token file path")
    private Path tokenFile;

    @Override
    public Integer call() throws Exception {
        TokenStore tokenStore = tokenFile != null ? new TokenStore(tokenFile) : TokenStore.defaultStore();
        String token = tokenStore.load();
        if (token == null || token.isBlank()) {
            System.err.println("未找到登录 token，请先运行: agent-cli login --username <user> --password <pw>");
            return 2;
        }

        BackendClient client = new BackendClient(url, token);
        SessionStore sessions = new SessionStore(dataDir);
        Map<String, Object> session = sessions.newSession(user);
        System.out.println("=== Intelligent Agent CLI (REPL) ===  (!help | !exit)");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }
            if (input.startsWith("!")) {
                if (handleCommand(client, sessions, session, input, reader)) {
                    break;
                }
                continue;
            }
            chatOnce(client, sessions, session, input);
        }
        return 0;
    }

    /** 处理 ! 命令；返回 true 表示退出 REPL。 */
    boolean handleCommand(BackendClient client, SessionStore sessions,
                          Map<String, Object> session, String input, BufferedReader reader)
            throws Exception {
        String[] parts = input.substring(1).trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "exit":
            case "quit":
                return true;
            case "help":
                printHelp();
                return false;
            case "models":
                client.models().forEach(System.out::println);
                return false;
            case "model":
                if (arg.isEmpty()) {
                    System.out.println("Usage: !model <name>");
                    return false;
                }
                System.out.println(client.switchModel(arg)
                        ? "Model switched to " + arg : "Switch failed");
                return false;
            case "personas":
                client.personas().forEach(p -> System.out.println(
                        p.get("role_id") + "\t" + p.get("name")));
                return false;
            case "persona":
                if (arg.isEmpty()) {
                    System.out.println("Usage: !persona <name>");
                    return false;
                }
                String roleId = resolveRoleId(client, arg);
                if (roleId == null) {
                    System.out.println("Persona not found: " + arg);
                    return false;
                }
                System.out.println(client.activatePersona(roleId)
                        ? "Persona activated: " + roleId : "Activate failed");
                return false;
            case "history":
                printHistory(session);
                return false;
            case "retract":
                retract(client, sessions, session, arg);
                return false;
            case "sessions":
                client.listConversations().forEach(s -> System.out.println(
                        s.get("session_id") + "\t" + s.get("preview")));
                return false;
            case "clear":
                session.clear();
                session.putAll(sessions.newSession(user));
                System.out.println("New session started.");
                return false;
            default:
                System.out.println("Unknown command: !" + cmd + "（!help 查看帮助）");
                return false;
        }
    }

    private void chatOnce(BackendClient client, SessionStore sessions,
                          Map<String, Object> session, String input) throws Exception {
        sessions.append(session, "user", input);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("use_tools", !noTools);
        options.put("use_memory", !noMemory);
        StringBuilder printed = new StringBuilder();
        String answer = client.stream(input, options, event -> {
            if ("token".equals(event.type())) {
                printed.append(unquote(event.data()));
                System.out.print(unquote(event.data()));
                System.out.flush();
            } else if ("error".equals(event.type())) {
                System.err.println("\n[error] " + unquote(event.data()));
            }
        });
        System.out.println();
        sessions.append(session, "assistant", answer);
        sessions.save(session);
    }

    private void retract(BackendClient client, SessionStore sessions,
                         Map<String, Object> session, String arg) throws Exception {
        if (arg.isEmpty()) {
            System.out.println("Usage: !retract <编号>[,<编号>...]（编号见 !history）");
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) session.getOrDefault("messages", new ArrayList<>());
        List<String> ids = new ArrayList<>();
        for (String token : arg.split(",")) {
            try {
                int index = Integer.parseInt(token.trim());
                if (index < 1 || index > messages.size()) {
                    System.out.println("编号 " + index + " 超出范围");
                    continue;
                }
                ids.add(String.valueOf(messages.get(index - 1).get("id")));
            } catch (NumberFormatException e) {
                System.out.println("编号格式错误: " + token);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        RetractResult result = client.retract(
                String.valueOf(session.get("session_id")), ids);
        messages.removeIf(m -> result.deletedIds().contains(String.valueOf(m.get("id"))));
        System.out.println("retracted=" + result.deleted() + " / requested=" + result.requested());
        sessions.save(session);
    }

    private static void printHistory(Map<String, Object> session) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) session.getOrDefault("messages", new ArrayList<>());
        if (messages.isEmpty()) {
            System.out.println("(empty history)");
            return;
        }
        int size = messages.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> message = messages.get(i);
            String role = "user".equals(message.get("role")) ? "用户" : "助手";
            System.out.println((i + 1) + ". [" + role + "] " + message.get("content"));
        }
    }

    private static String resolveRoleId(BackendClient client, String persona) throws Exception {
        for (Map<String, String> p : client.personas()) {
            if (persona.equals(p.get("role_id")) || persona.equals(p.get("name"))) {
                return p.get("role_id");
            }
        }
        return null;
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

    private static void printHelp() {
        System.out.println("""
                !models            List available models
                !model <name>      Switch model
                !personas          List available personas
                !persona <name>    Switch persona
                !history           Show recent conversation (numbered)
                !retract <编号>     Permanently delete message(s) by number
                !sessions          List saved sessions
                !clear             Start a new session
                !exit / !quit      Exit the REPL
                """);
    }
}
