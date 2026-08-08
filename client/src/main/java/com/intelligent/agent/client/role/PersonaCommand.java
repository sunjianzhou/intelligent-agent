package com.intelligent.agent.client.role;

import com.intelligent.agent.client.auth.TokenStore;
import com.intelligent.agent.client.http.BackendClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 角色管理命令：agent-cli persona list / agent-cli persona activate <name>。
 */
@Command(name = "persona", description = "Persona management",
        subcommands = {PersonaCommand.ListCommand.class, PersonaCommand.ActivateCommand.class})
public class PersonaCommand {

    @Command(name = "list", description = "List available personas")
    public static class ListCommand implements Callable<Integer> {
        @Option(names = "--url", defaultValue = "http://localhost:8080")
        private String url;

        @Option(names = "--token-file", description = "Token file path")
        private java.nio.file.Path tokenFile;

        @Override
        public Integer call() throws Exception {
            List<Map<String, String>> personas = client(url, tokenFile).personas();
            if (personas.isEmpty()) {
                System.out.println("(no personas)");
                return 0;
            }
            personas.forEach(p -> System.out.println(
                    p.get("role_id") + "\t" + p.get("name")));
            return 0;
        }
    }

    @Command(name = "activate", description = "Activate a persona (by role_id or name)")
    public static class ActivateCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Persona role_id or name")
        private String persona;

        @Option(names = "--url", defaultValue = "http://localhost:8080")
        private String url;

        @Option(names = "--token-file", description = "Token file path")
        private java.nio.file.Path tokenFile;

        @Override
        public Integer call() throws Exception {
            BackendClient client = client(url, tokenFile);
            String roleId = resolveRoleId(client, persona);
            if (roleId == null) {
                System.err.println("Persona not found: " + persona);
                return 1;
            }
            boolean ok = client.activatePersona(roleId);
            System.out.println(ok ? "Persona activated: " + roleId : "Activate failed");
            return ok ? 0 : 1;
        }

        private static String resolveRoleId(BackendClient client, String persona) throws Exception {
            for (Map<String, String> p : client.personas()) {
                if (persona.equals(p.get("role_id")) || persona.equals(p.get("name"))) {
                    return p.get("role_id");
                }
            }
            return null;
        }
    }

    static BackendClient client(String url, java.nio.file.Path tokenFile) {
        TokenStore store = tokenFile != null ? new TokenStore(tokenFile) : TokenStore.defaultStore();
        String token = store.load();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("未找到登录 token，请先运行 agent-cli login");
        }
        return new BackendClient(url, token);
    }
}
