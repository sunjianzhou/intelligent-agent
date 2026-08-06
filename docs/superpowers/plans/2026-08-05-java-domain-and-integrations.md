# Java Domain and Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move memory, domain APIs, schedules, and external channels into the single Java backend while retaining every public contract.

**Architecture:** `ai.memory` owns memory policies and only uses `infrastructure.vectorstore` through a port. Local services replace proxy controllers by vertical slice. Explicitly named integration packages own their external protocols.

**Tech Stack:** Java 21, Spring Boot, Spring AI vector-store abstraction, Spring Scheduler, WebClient, JUnit 5, MockWebServer.

## Global Constraints

- Plan 1 and its regression suite must pass first.
- Every read/write filters by user, role, and project where applicable.
- Java never reads ChromaDB's internal SQLite/HNSW layout.
- Shadow mode never double-executes a message delivery or write tool.

---

## File Structure

- `ai/memory`: context, short-term state, distillation, cache, and repository port.
- `infrastructure/vectorstore`: chosen vector-store implementation only.
- `domain/{role,conversation,project,task,skill,knowledge,analytics,teaching}`: business services.
- `integration/{comfyui,mcp,feishu,wechat,telegram}`: external protocols.

### Task 1: Establish memory and vector-store ports

**Files:** Create `ai/memory/MemoryRepository.java`, `MemoryRecord.java`, `infrastructure/vectorstore/VectorMemoryRepository.java`, and `src/test/java/.../ai/memory/MemoryRepositoryContractTest.java`.

**Produces:** `upsert`, scoped `search`, and scoped `delete` operations.

- [ ] Write the failing isolation test.

```java
@Test void searchNeverReturnsAnotherUsersMemory() {
  repository.upsert(new MemoryRecord("m1", "alice", "secret", Map.of()));
  assertThat(repository.search("bob", "secret", 5)).isEmpty();
}
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=MemoryRepositoryContractTest test`.
- [ ] Implement the port with filterable `user_id`, `role_id`, `project_id`, `type`, and `importance`; rerun the test.
- [ ] Commit with `feat: add Java memory repository port`.

### Task 2: Migrate conversation memory, RAG, and cache

**Files:** Create `ai/memory/ConversationMemoryService.java`, `MemoryDistillationService.java`, `SemanticResponseCache.java`, and `ConversationMemoryServiceTest.java`; modify `ai/agent/AgentOrchestrator.java`.

**Produces:** `AgentContext loadContext(AgentRequestContext)` and `recordTurn(AgentRequestContext, String)`.

- [ ] Write the failing persona cache-isolation test.

```java
@Test void cacheKeyIncludesPersona() {
  cache.put("u", "writer", "q", "a");
  assertThat(cache.get("u", "coder", "q")).isEmpty();
}
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=ConversationMemoryServiceTest test`.
- [ ] Implement short-term history, five-turn distillation, ten-turn summaries, project retrieval, and persona/model-aware cache keys; rerun the test.
- [ ] Commit with `feat: migrate conversation memory and RAG`.

### Task 3: Replace role, conversation, project, and task proxies

**Files:** Create `domain/role/RoleService.java`, `domain/conversation/ConversationService.java`, `domain/project/ProjectService.java`, `domain/task/TaskService.java`, `DomainApiContractTest.java`; modify `RoleController.java`, `ConversationsProxyController.java`, `ProjectProxyController.java`, and `TaskProxyController.java`.

**Produces:** local implementations for the existing API paths and DTO shapes.

- [ ] Write a failing contract test for each vertical slice before replacing its proxy.

```java
mockMvc.perform(get("/api/projects").header("Authorization", token))
  .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=DomainApiContractTest test`.
- [ ] Implement a local service and switch exactly one matching controller; repeat role → conversation → project → task.
- [ ] Run the contract suite after each slice and commit each slice separately with `feat: migrate <slice> API`.

### Task 4: Migrate knowledge, skills, analytics, and teaching

**Files:** Create `domain/knowledge/KnowledgeService.java`, `domain/skill/SkillService.java`, `domain/analytics/AnalyticsService.java`, `domain/teaching/TeachingService.java`, `KnowledgeAndSkillContractTest.java`; modify matching proxy controllers.

**Produces:** local knowledge chunking/indexing, business CRUD, analytics, and teaching responses.

- [ ] Write the failing multipart upload contract including an oversized 413 case.

```java
mockMvc.perform(multipart("/api/knowledge/upload").file("file", "text".getBytes()))
  .andExpect(status().isOk());
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=KnowledgeAndSkillContractTest test`.
- [ ] Implement sentence-aware chunking through `MemoryRepository`, then migrate remaining endpoint families one at a time.
- [ ] Run `cd backend/web; ./mvnw.cmd test`; commit with `feat: migrate knowledge skill analytics teaching APIs`.

### Task 5: Migrate scheduler and named integrations

**Files:** Create `infrastructure/scheduler/TaskSchedulerService.java`, `integration/comfyui/ComfyUiClient.java`, `integration/mcp/McpToolRegistry.java`, `integration/feishu/FeishuChannelClient.java`, `integration/wechat/WeChatChannelClient.java`, `integration/telegram/TelegramChannelClient.java`, and `ChannelDeduplicationTest.java`.

**Produces:** `ChannelClient.send(ChannelMessage): DeliveryResult`, idempotent broadcast, rate limits, retries, callback verification, and ComfyUI progress.

- [ ] Write the failing duplicate-delivery test.

```java
assertThat(router.broadcast(message).deliveries()).allMatch(DeliveryResult::accepted);
verify(feishu, times(1)).send(message);
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=ChannelDeduplicationTest test`.
- [ ] Implement named clients, OAuth token persistence, rate limits, and retry rules; rerun the focused test with MockWebServer.
- [ ] Commit with `feat: migrate schedules and named integrations`.
