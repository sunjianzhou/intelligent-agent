# Java CLI, Cutover, and Python Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Python CLI, migrate logical data safely, cut traffic to Java, and retire project-owned Python only after rollback-ready acceptance.

**Architecture:** `client/` becomes an independent Java Maven executable. Migration utilities consume logical exports, not Chroma internals. Feature flags make each cohort cutover reversible.

**Tech Stack:** Java 21, Maven, Picocli, Java HTTP Client, Jackson, JUnit 5, Docker Compose.

## Global Constraints

- Plans 1 and 2 must be complete.
- The CLI must not store `JWT_SECRET` or sign its own bearer JWT.
- Migration reports are timestamped, immutable, and contain no OAuth secrets.
- Do not delete Python data, volumes, or source without explicit owner approval.

---

### Task 1: Create Java CLI and secure login

**Files:** Create `client/pom.xml`, `client/src/main/java/com/intelligent/agent/client/Main.java`, `auth/TokenStore.java`, and `src/test/java/.../MainTest.java`; modify backend `AuthController.java` for scoped CLI tokens.

**Produces:** `agent-cli login` and `agent-cli chat`, with token storage outside version control.

- [ ] Write the failing command-discovery test.

```java
@Test void exposesChatCommand() {
  assertThat(new CommandLine(new Main()).getSubcommands()).containsKey("chat");
}
```

- [ ] Run `cd client; ../backend/web/mvnw.cmd -Dtest=MainTest test`.
- [ ] Implement Picocli commands and restrictive token-file permissions; rerun the test.
- [ ] Commit with `feat: add Java CLI authentication`.

### Task 2: Implement chat streaming and local sessions

**Files:** Create `http/BackendClient.java`, `chat/ChatCommand.java`, `chat/SseEventParser.java`, `session/SessionStore.java`, and `SseEventParserTest.java`.

**Produces:** existing `POST /api/chat/stream` event rendering and JSON local session files.

- [ ] Write the failing SSE parser test.

```java
assertThat(parser.parse("data: {\"type\":\"token\",\"data\":\"hi\"}").type())
  .isEqualTo("token");
```

- [ ] Run `cd client; ../backend/web/mvnw.cmd -Dtest=SseEventParserTest test`.
- [ ] Implement unbuffered Java HTTP line streaming and event rendering; rerun the test.
- [ ] Commit with `feat: add Java CLI chat streaming and sessions`.

### Task 3: Reach CLI feature parity

**Files:** Create `chat/ReplCommand.java`, `model/ModelCommand.java`, `role/PersonaCommand.java`, `conversation/RetractCommand.java`, and `ClientApiContractTest.java`.

**Produces:** documented `!models`, `!model`, `!personas`, `!persona`, `!history`, `!retract`, `!sessions`, `!clear`, and `!exit` behavior.

- [ ] Write the failing retraction contract test.

```java
assertThat(client.retract("session", List.of("m1")).success()).isTrue();
```

- [ ] Run `cd client; ../backend/web/mvnw.cmd -Dtest=ClientApiContractTest test`.
- [ ] Implement each command against stable Java backend paths, run `cd client; ../backend/web/mvnw.cmd test`, and commit with `feat: complete Java CLI compatibility`.

### Task 4: Build validated logical-data migration

**Files:** Create `backend/web/src/main/java/com/intelligent/agent/web/infrastructure/migration/LegacyExportManifest.java`, `LegacyDataImporter.java`, `MigrationValidator.java`, and `MigrationValidatorTest.java`; create `docs/migration/.gitkeep`.

**Produces:** import validation based on source record count and SHA-256, plus re-embedding into the Java vector store.

- [ ] Write the failing manifest-mismatch test.

```java
assertThatThrownBy(() -> validator.validate(manifest, imported))
  .isInstanceOf(MigrationValidationException.class);
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=MigrationValidatorTest test`.
- [ ] Implement JSONL manifest/import validation; export Python logical records before import; dry-run against copied volumes.
- [ ] Rerun the test and commit with `feat: add validated legacy data migration`.

### Task 5: Perform shadow verification and staged cutover

**Files:** Create `infrastructure/observability/ShadowComparisonRecorder.java` and its test; modify backend `application.yml` and `docker-compose.yml`.

**Produces:** redacted comparisons of wire shape, tool trace hash, retrieval ids, and latency; `python`, `shadow`, and allowlisted `java` modes.

- [ ] Write the failing redaction test.

```java
assertThat(recorder.record(result).toJson()).doesNotContain("Bearer ").doesNotContain("private prompt");
```

- [ ] Run `cd backend/web; ./mvnw.cmd -Dtest=ShadowComparisonRecorderTest test`.
- [ ] Implement allowlisted Java routing and read-only shadow comparison; do not mirror a side effect.
- [ ] Run `cd backend/web; ./mvnw.cmd test; cd ../../frontend; npm run test; cd ../tests/e2e; pytest -v` and commit with `feat: add safe Java runtime cutover controls`.

### Task 6: Retire Python after signed acceptance

**Files:** Modify `docker-compose.yml`, `start_all.bat`, `start_all.sh`, `README.md`, `AI_PROJECT_CONTEXT.md`, and `client/README.md`; delete `agent/` and Python CLI files only after approval.

**Produces:** Java-only startup with archived migration evidence.

- [ ] Create an acceptance record confirming data reconciliation, restore rehearsal, Java backend/CLI E2E, IM delivery, closed rollback window, and owner deletion authorization.
- [ ] Do not execute deletion before all six confirmations.
- [ ] Remove Agent service/dependencies and update docs only after authorization.
- [ ] Run `docker compose config; cd backend/web; ./mvnw.cmd test; cd ../../frontend; npm run test; cd ../tests/e2e; pytest -v`.
- [ ] Commit retirement separately with `chore: retire Python agent and CLI`.
