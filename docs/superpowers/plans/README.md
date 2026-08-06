# Java Migration Execution Queue

Read the design first: [`../specs/2026-08-05-java-unification-design.md`](../specs/2026-08-05-java-unification-design.md).

Execute these plans strictly in order. A checked task requires its stated test
command and a focused commit. Do not delete `agent/` or change a public API
before the final plan's acceptance gates pass.

- [ ] [Plan 1 — Java backend foundation and AI runtime](2026-08-05-java-backend-ai-runtime.md)
- [ ] [Plan 2 — Memory, domain APIs, scheduler, and integrations](2026-08-05-java-domain-and-integrations.md)
- [ ] [Plan 3 — Java CLI, cutover, data migration, and retirement](2026-08-05-java-client-cutover-retirement.md)

`TODOS.md` already has unrelated uncommitted edits. This index is therefore the
authoritative migration queue until that file's existing change is merged.
