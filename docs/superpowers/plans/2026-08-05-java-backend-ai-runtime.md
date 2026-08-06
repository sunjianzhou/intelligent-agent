# Java Backend AI Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `backend/web` serve compatible chat and streaming responses locally, without calling the Python Agent.

**Architecture:** Preserve the HTTP/WebSocket boundary while introducing `ai.agent`, `ai.llm`, `ai.tool`, and `ai.memory`. Python stays available only as a feature-flagged fallback or shadow reference until cutover.

**Tech Stack:** Java 21, current Spring Boot 3.x line, Spring AI, Spring MVC/WebSocket, JUnit 5, MockWebServer, Maven.

## Global Constraints

- Keep `/api/*`, `/ws`, and SSE event names/JSON fields unchanged.
- Do not modify Vue or remove the Python Agent in this plan.
- Request/user context must never be held in mutable singleton fields.
- Shadow mode must deny tools with side effects.

---

## File Structure

- `ai/llm`: model-neutral request/event contracts and Ollama/cloud adapters.
- `ai/tool`: typed tools, legacy text-call parser, and bounded execution.
- `ai/agent`: ReAct orchestration and compatibility response mapping.
- `api/chat`: HTTP/SSE adapters only.

### Task 1: Upgrade the Java baseline

**Files:** Modify `backend/web/pom.xml`, `backend/web/Dockerfile`, and `backend/web/src/main/resources/application.yml`; create `backend/web/src/test/java/com/intelligent/agent/web/BuildBaselineTest.java`.

**Produces:** Java 21 compilation and `ai.runtime.mode` with `python`, `shadow`, or `java`.

- [ ] Write the failing configuration test.

```java
@SpringBootTest(properties = "ai.runtime.mode=java")
class BuildBaselineTest {
  @Value("${ai.runtime.mode}") String mode;
  @Test void loadsJavaRuntimeMode() { assertThat(mode).isEqualTo("java"); }
}
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=BuildBaselineTest test` and observe the pre-upgrade failure.
- [ ] Upgrade the parent/BOM to a current Spring Boot 3.x line, set `<java.version>21</java.version>`, add Spring AI dependency management, update Docker to JDK/JRE 21, and add:

```yaml
ai:
  runtime:
    mode: python
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=BuildBaselineTest test`; commit with `build: upgrade backend to Java 21 baseline`.

### Task 2: Add provider-neutral LLM contracts

**Files:** Create `ai/llm/ChatTurn.java`, `ModelEvent.java`, `LlmProvider.java`; create `src/test/java/.../ai/llm/ModelEventTest.java`.

**Produces:** `Flux<ModelEvent> stream(ChatTurn)` and `Mono<String> complete(ChatTurn)`; events are limited to `token`, `tool_call_start`, `tool_call`, `tool_calls_done`, `done`, and `error`.

- [ ] Write the failing serialization test.

```java
@Test void tokenUsesExistingSseShape() throws Exception {
  assertThat(mapper.writeValueAsString(ModelEvent.token("你好")))
      .contains("\"type\":\"token\"").contains("\"data\":\"你好\"");
}
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=ModelEventTest test` and verify failure.
- [ ] Implement immutable records, then run the same command and commit with `feat: add model streaming contracts`.

### Task 3: Implement Ollama and cloud providers

**Files:** Create `ai/llm/ollama/OllamaLlmProvider.java`, `ai/llm/cloud/OpenAiCompatibleLlmProvider.java`, `ai/llm/LlmProviderRouter.java`; create `LlmProviderRouterTest.java`.

**Produces:** `LlmProviderRouter.forUser(String userId, String requestedModel)` with request-specific model/options.

- [ ] Write the failing router test.

```java
@Test void resolvesCloudProviderForConfiguredCloudModel() {
  assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(cloud);
}
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=LlmProviderRouterTest test`.
- [ ] Implement model routing and map Ollama/cloud chunks to `ModelEvent`; redact provider credentials from errors.
- [ ] Verify with MockWebServer using the same focused command; commit with `feat: add local and cloud LLM adapters`.

### Task 4: Build the tool kernel

**Files:** Create `ai/tool/ToolDefinition.java`, `AgentTool.java`, `ToolExecutor.java`, `TextToolCallParser.java`; create `ToolExecutorTest.java`.

**Produces:** `ToolExecutor.execute(ToolCall, ToolExecutionContext): ToolResult`, five-round limit, and four legacy parsers (JSON, tag, fenced JSON, plain text).

- [ ] Write the failing shadow-mode test.

```java
@Test void deniesWriteToolInShadowMode() {
  assertThat(executor.execute(writeCall, shadowContext).status()).isEqualTo("denied");
}
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=ToolExecutorTest test`.
- [ ] Implement `readOnly`, `requiredRole`, and `timeout` metadata plus the parser set; rerun the test and commit with `feat: add bounded tool execution kernel`.

### Task 5: Implement local ReAct orchestration

**Files:** Create `ai/agent/AgentOrchestrator.java`, `AgentRequestContext.java`, `api/chat/LocalChatService.java`; modify `service/AgentService.java`; create `AgentOrchestratorTest.java`.

**Produces:** `Flux<ModelEvent> AgentOrchestrator.stream(AgentRequestContext)` and mode switching inside existing `AgentService`.

- [ ] Write the failing no-tool event-order test.

```java
StepVerifier.create(orchestrator.stream(context))
  .expectNextMatches(e -> e.type().equals("token"))
  .expectNextMatches(e -> e.type().equals("done"))
  .verifyComplete();
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=AgentOrchestratorTest test`.
- [ ] Compose context, run at most five tool rounds, emit established events, and retain Python behavior when mode is `python`.
- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=AgentOrchestratorTest,ChatControllerTest test`; commit with `feat: run chat orchestration in Java`.

### Task 6: Lock public chat contracts

**Files:** Create `src/test/java/.../api/chat/ChatContractTest.java` and `src/test/resources/contracts/chat-stream-events.jsonl`; modify `controller/ChatController.java` only if needed.

**Produces:** regression coverage for `/api/chat` and `/api/chat/stream` payloads.

- [ ] Write the failing non-stream contract test.

```java
mockMvc.perform(post("/api/chat").contentType(APPLICATION_JSON).content("{\"message\":\"hi\"}"))
  .andExpect(status().isOk()).andExpect(jsonPath("$.response").isString());
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=ChatContractTest test`.
- [ ] Map internal events to wire events in the controller; rerun the focused test and then `cd backend/web; ./mvnw.cmd test`.
- [ ] Commit with `test: lock chat API and SSE contracts`.
