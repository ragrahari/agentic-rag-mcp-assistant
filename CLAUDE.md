# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**M0 through M3 are complete.** The Spring Boot project has a working `/chat` SSE streaming endpoint with JDBC-backed chat memory, a minimal static HTML page at `/`, tenant-scoped RAG retrieval via pgvector + `QuestionAnswerAdvisor`, and a tenant-scoped MCP tool call (`getCardBalance`) served by a standalone second process, `mcp-card-server/`. `POST`/`DELETE /ingest/{tenantId}` manage per-tenant chunks in pgvector, idempotently. Two tenants (`acme`, `globex`) have distinct sample docs under `rag-docs/` and distinct seeded account/transaction rows. A `RagEvalTest` integration test scores retrieval precision@k and asserts cross-tenant isolation. Chat memory and the vector store share one Postgres instance (see Architecture below for why), and chat memory is itself tenant-namespaced (see "Tenant isolation" below) — not just the vector store. There is no router yet — RAG and MCP are both always available per request; the model decides whether to call the tool. Current milestone: **M4** (router + guardrails + observability).

## Commands

- Run main app: `docker compose up -d && ./mvnw spring-boot:run` — starts on `http://localhost:8080`. Requires Ollama running locally with `llama3.1:8b` and `nomic-embed-text` pulled, and Docker (Postgres + pgvector) up.
- Run MCP card server (separate process, needed for the MCP path — not required for RAG or for the main app to start): `cd mcp-card-server && ./mvnw spring-boot:run` — starts on `http://localhost:8081`, seeds its own `accounts`/`transactions` tables on startup.
- Compile only: `./mvnw compile` (run from the relevant module — `mcp-card-server` is a fully separate Maven project, not a submodule)
- Test: `./mvnw test` — currently just `RagEvalTest`, an integration test requiring the live stack (Postgres + Ollama); it ingests both tenants itself. Run in isolation with `./mvnw test -Dtest=RagEvalTest`.

## Source of truth

`PLAN.md` in the repo root is the authoritative project plan. Read it before proposing any change. Do not deviate from its locked scope decisions (Section 2) without flagging it explicitly.

## Working relationship

The human is the orchestrator and owns all architectural decisions (schema, routing logic, guardrail policy, retrieval strategy — the "what" and "why"). Your job is implementation (the "how"): boilerplate, wiring, tests, refactors.

When you would make an architectural choice, stop and surface it as a question with the tradeoffs, rather than deciding silently. Prefer small, reviewable changes. Keep `main` runnable at all times.

## Constraints

- Free/local model stack only (Ollama). Never introduce a paid API dependency.
- Never commit secrets. Never hardcode config that `PLAN.md` says belongs in `application.yml`.
- Do not implement future-work items (`PLAN.md` Section 4) unless explicitly asked.

## What this project is

A Spring AI agentic assistant for a fintech context (Navan-style business-travel-fintech framing) that routes each request through the right retrieval strategy — internal knowledge via RAG, live data via MCP, both, or a guarded refusal — with real per-tenant data isolation. It's built as a public, staff-level portfolio artifact demonstrating agentic orchestration, retrieval tuning, and tenant-isolated design (explicitly not aimed at production hardening or real product-market fit).

## Locked scope decisions (do not reopen without a conscious decision)

- **Single domain: fintech.** No domain-classification logic, no domain metadata field.
- **Tenant isolation is real and in-scope.** Every chunk carries a `tenantId` metadata field; retrieval is *always* scoped to the requesting tenant. A cross-tenant retrieval is a data leak, not a bug.
- **Folder-name = tenant convention.** `rag-docs/<tenantId>/` holds all of a tenant's documents (mixed types — policy, business intro, product guide — ingested identically). Document *type* is not modeled.
- **Ingestion is `ingest(tenantId, source)` behind a stable seam.** Local folder now; the seam allows S3/blob or API-driven ingestion later with no change to the retrieval side.
- **Model stack is free/local.** Ollama for chat + embeddings; no paid API keys.
- **Tenant is carried on the request** (header or path/param) so the same question returns different, tenant-scoped answers.

Struck from scope (do not build unless the plan changes): multi-domain support, domain filtering, per-document-type logic, API/connector-based ingestion (fast-follow, not now), incremental/CDC sync (future work).

## Architecture

```
User (browser chat)  ──►  /chat  ──►  Guardrail pre-check ──► DENY? ─► canned refusal + reason
                                            │
                                            ▼
                                     Path Router (structured output)
                                     { path: RAG | MCP | BOTH | DENY }
                                            │
                        ┌───────────────────┼───────────────────┐
                        ▼                   ▼                   ▼
                      RAG                  MCP                 BOTH
             (pgvector, tenant-      (Spring AI MCP      (run both, merge
              scoped retrieval)       client → server)    context, degrade
                        │                   │             gracefully on fail)
                        └───────────────────┼───────────────────┘
                                            ▼
                                 Final generation call
                              (assembled context + chat memory)
                                            │
                                            ▼
                                    Streamed response
```

**Cross-cutting from day one:** structured logging of the routing decision, retrieved chunks (ids + tenant), and the final assembled prompt — when output is wrong, it must be possible to tell whether it was routing, retrieval, or generation.

### Stack
- Java 21, Spring Boot 3.x, Spring AI 1.0.x
- Ollama: chat model `llama3.1:8b` + embedding model `nomic-embed-text`
- Postgres + pgvector (`spring-ai-pgvector-store` starter)
- Chat memory: JDBC-backed `ChatMemory` (survives restarts)
- UI: single static HTML page hitting `/chat` over SSE — no frontend framework

### Environment
Mac Mini M4, 16GB RAM, no memory constraints — `llama3.1:8b`, Postgres in Docker, the Spring Boot JVM, and the IDE can all run at once without issue. Postgres runs via Docker for portability (`docker-compose.yml` at repo root); start with `docker compose up -d` before running the app. Required from M2 onward — both chat memory and the pgvector store depend on it (M1's standalone H2 chat memory was replaced, see Architecture below).

### Config externalization
All RAG tuning knobs (chunk size, top-K, similarity threshold, embedding model name) belong in `application.yml`, never hardcoded. "Tuning" must never mean "recompile." Note: Spring AI 1.0.9's `TokenTextSplitter` has no chunk-overlap parameter (chunks are back-to-back, not sliding-window) — "chunk overlap" from the original plan isn't a real knob in this library version, so it's not implemented.

### Datastore consolidation (M2)
`PgVectorStoreAutoConfiguration` binds to Spring Boot's single primary `DataSource` — it has no independent connection config. Introducing pgvector therefore moved M1's JDBC chat memory off its standalone H2 file database onto the same Postgres instance (no way to keep two datastores without manually wiring a second `DataSource` for no real benefit). This is a net simplification: Postgres is a first-class supported dialect for Spring AI's JDBC chat memory, unlike H2 which needed a schema-borrowing workaround in M1. Consequence: the app now requires `docker compose up -d` (Postgres) to start at all.

### Example routing scenarios
See `PLAN.md` Section 6 for the full list. One per path, for calibration when building the router/guardrails (M4):
- **RAG:** "What's our per-diem limit for international travel?"
- **MCP:** "What's the current balance on my corporate card?"
- **BOTH:** "I spent $340 on dinner last night — is that within policy?" (MCP fetches the transaction, RAG fetches the policy, generation combines them)
- **DENY:** "What's [another tenant]'s total travel spend?" (cross-tenant — this is where tenancy and guardrails visibly connect)

## Ingestion design (the extensibility seam)

**Current state:** `IngestionService.ingest(tenantId)` reads every file directly under `rag-docs/<tenantId>/` (folder-name = tenant convention), splits with `TokenTextSplitter`, embeds, and upserts into pgvector with `tenantId` stamped into each chunk's metadata. `IngestionService.deleteTenant(tenantId)` removes only that tenant's chunks via `vectorStore.delete(Filter.Expression)`. Exposed via `POST`/`DELETE /ingest/{tenantId}`; `POST /ingest/{tenantId}` 404s if that tenant's folder doesn't exist. Idempotent: deterministic chunk id = `UUID.nameUUIDFromBytes(tenantId + "#" + relativeSourcePath + "#" + chunkIndex)`, relying on `PgVectorStore.add()`'s `ON CONFLICT (id) DO UPDATE`.

- **Provisioning is data, not schema:** shared vector store, `tenantId` as metadata — no new table/migration per tenant.

## Tenant isolation (M2 part 2)

- **Retrieval:** `ChatController` builds a fresh `QuestionAnswerAdvisor` per `/chat` request (not a shared `defaultAdvisor`), with `SearchRequest.filterExpression` set to `FilterExpressionBuilder().eq("tenantId", tenantId)` where `tenantId` comes from the required `X-Tenant-Id` header. Built as an AST object directly from a trusted server-side value, never as filter text — `QuestionAnswerAdvisor`'s string-based filter-override path (`qa_filter_expression` context key) is never populated by this code, so it's not a reachable bypass. pgvector ANDs the filter directly into the SQL `WHERE` clause (not a post-filter), so the database itself never returns another tenant's rows. 
- **Not covered:** there is no authentication yet verifying a caller may claim a given `X-Tenant-Id` — that's an authz gap for M4, not a filter-bypass bug.
- **Chat memory:** namespaced by tenant too, not just `conversationId`. The actual `ChatMemory.CONVERSATION_ID` used is `UUID.nameUUIDFromBytes(tenantId + "::" + conversationId)` (`conversationId` alone is a client-chosen browser-tab UUID, and without this, reusing the same `conversationId` under two tenants would read/write the same memory row — a leak the vector-store filter doesn't touch). Hashed into a UUID rather than stored as the literal concatenation because `SPRING_AI_CHAT_MEMORY.conversation_id` is `VARCHAR(36)` (sized for a raw UUID by Spring AI's shipped schema) — plain concatenation overflowed it in production as soon as a real question hit it (`DataIntegrityViolationException: value too long for type character varying(36)`). Verified directly (same `conversationId`, tenant switched acme→globex→acme): zero bleed-through either direction, memory intact within a tenant.
- **Eval harness** (`RagEvalTest`) asserts both: every retrieved doc's `tenantId` metadata matches the requesting tenant for every eval case, plus a dedicated test that the same query under two tenants returns disjoint chunk-id sets.

## MCP path (M3)

- **Two separate Spring Boot processes**, not a Maven multi-module build: `mcp-card-server/` has its own `pom.xml`/`mvnw`, own port (8081), depends on nothing from the main app. Both point at the same Postgres instance but own disjoint tables (`vector_store`/`SPRING_AI_CHAT_MEMORY` vs `accounts`/`transactions`).
- **Transport: HTTP+SSE** (`spring-ai-starter-mcp-server-webmvc` / `spring-ai-starter-mcp-client`), not stdio — stdio couples the server's lifecycle to the client process, which fights the "kill the server independently" validation scenario.
- **Tool registration:** `@Tool`-annotated method (`CardBalanceTools.getCardBalance`) wrapped in a `MethodToolCallbackProvider` bean on the server; the webmvc server autoconfiguration exposes any `ToolCallbackProvider`/`ToolCallback` bean automatically. Client-side discovery is a live `list_tools` call.
- **Tenant scoping mirrors the RAG filter pattern:** `TenantScopedToolCallback` (built fresh per `/chat` request from the `X-Tenant-Id` header, same as the per-request `QuestionAnswerAdvisor`) strips `tenantId` from the schema the model sees and force-overwrites it in the arguments before every call — never trusts the model to supply or preserve it.
- **Graceful degradation, two distinct failure modes, both fixed and verified by actually killing the process:**
  - *Server down at boot:* Spring AI's auto-registered `ToolCallbackProvider` bean, merely by existing, makes an unrelated bean (`ToolCallingAutoConfiguration.toolCallbackResolver`, built while wiring the Ollama chat model) eagerly call `list_tools` during context startup — independent of `spring.ai.mcp.client.initialized` and independent of `@Lazy` on any of our own injection points. This failed the *whole app's* startup with the card server down. Fixed with `spring.ai.mcp.client.toolcallback.enabled=false` (disables that bean) plus `ChatController` building its own `SyncMcpToolCallbackProvider` from the raw `McpSyncClient` list per request, inside a try/catch.
  - *Server killed mid-conversation:* `TenantScopedToolCallback.call()` catches any failure and returns a clean non-throwing error string. `spring.ai.mcp.client.request-timeout: 5s` bounds the wait.
- **Known, expected limitation (not a bug):** the model doesn't always choose to call the tool — observed that a nearby RAG-retrieved chunk (e.g. the card program guide) plus `QuestionAnswerAdvisor`'s strict "answer using only the context" template sometimes makes the model decline to call the tool even when offered. This exact ambiguity is what M4's router resolves; M3 only needed to prove the tool call works correctly and safely when invoked.
- **`GET /mcp/tools`** (`McpToolsController`) is a read-only diagnostic endpoint: same raw discovery as `ChatController` (`new SyncMcpToolCallbackProvider(mcpSyncClients).getToolCallbacks()`), but *not* wrapped in `TenantScopedToolCallback` — it deliberately shows each tool's real schema (including `tenantId`), since this is an observability endpoint, not a tenant-facing one. Never errors: MCP server down → HTTP 200, `{"mcpServerAvailable":false,"tools":[]}`.

Documented-but-not-built future work (belongs in README's "future work" section, not in code): API-driven ingestion (`POST /tenants/{id}/documents`), source connectors (S3/Drive/Confluence), incremental event-driven (CDC/outbox) sync.

## Milestones

Each milestone must be working, demoable, and committable to `main`, with its own validation gate (see `PLAN.md` §5 for full validation criteria per milestone):

- **M0** — Repo + skeleton: Spring Boot project scaffolded, README with architecture diagram, Ollama running locally.
- **M1** — Chat endpoint, streaming, memory: `/chat` → Ollama, SSE streaming, JDBC-backed chat memory, minimal HTML page.
- **M2** — RAG path + tenant isolation + minimal eval: ingestion, pgvector, `QuestionAnswerAdvisor`, tenant-scoped retrieval, config-externalized tuning, ingest endpoints, ~5 Q&A eval pairs scoring precision@k. **Repo flips to public here**, once `main` runs clean and isolation holds.
- **M3** — MCP path: one custom MCP server (a single mocked tool, e.g. card balance), Spring AI MCP client wiring, timeout/failure policy.
- **M4** — Router + guardrails + observability: structured-output path router (RAG/MCP/BOTH/DENY + reason), guardrail pre-check (policy, prompt-injection, cross-tenant access), full decision-trail logging.
- **M5** — BOTH path + merge: run RAG + MCP, merge context, generate; degrade to single-path on failure.
- **M6** — RAG tuning pass: sweep chunk size/overlap/top-K/threshold/embedding model against the eval set, add hybrid retrieval (vector + Postgres full-text), document before/after precision@k tradeoffs.

## Working conventions

- Ship sequentially: bias toward demoable progress at each step over completeness. Every milestone should be runnable and committable to `main`.
- Experimental thrash happens on branches; `main` always runs clean.
- Commits are meaningful — no `TODO: fix hack` on `main`.
- The eval harness (M2) and the tuning comparison table (M6) are first-class deliverables, not incidental artifacts — they're the strongest evidence of deliberate retrieval-tuning work.
- README framing: state outcomes as capability (what the system does, what was built) — never as a learning/practice project.
