package com.intelligent.agent.client.model;

import com.intelligent.agent.client.auth.TokenStore;
import com.intelligent.agent.client.http.BackendClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 模型管理命令：agent-cli model list / agent-cli model switch <name>。
 */
@Command(name = "model", description = "Model management",
        subcommands = {ModelCommand.ListCommand.class, ModelCommand.SwitchCommand.class})
public class ModelCommand {

    @Command(name = "list", description = "List available models")
    public static class ListCommand implements Callable<Integer> {
        @Option(names = "--url", defaultValue = "http://localhost:8080")
        private String url;

        @Option(names = "--token-file", description = "Token file path")
        private java.nio.file.Path tokenFile;

        @Override
        public Integer call() throws Exception {
            BackendClient client = client(url, tokenFile);
            List<String> models = client.models();
            if (models.isEmpty()) {
                System.out.println("(no models available)");
                return 0;
            }
            models.forEach(System.out::println);
            return 0;
        }
    }

    @Command(name = "switch", description = "Switch to a different model")
    public static class SwitchCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Model name")
        private String modelName;

        @Option(names = "--url", defaultValue = "http://localhost:8080")
        private String url;

        @Option(names = "--token-file", description = "Token file path")
        private java.nio.file.Path tokenFile;

        @Override
        public Integer call() throws Exception {
            boolean ok = client(url, tokenFile).switchModel(modelName);
            System.out.println(ok ? "Model switched to " + modelName : "Switch failed");
            return ok ? 0 : 1;
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
