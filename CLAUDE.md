# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**M0 through M4 are complete.** The Spring Boot project has a working `/chat` SSE streaming endpoint with JDBC-backed chat memory, a minimal static HTML page at `/`, tenant-scoped RAG retrieval via pgvector + `QuestionAnswerAdvisor`, and a tenant-scoped MCP tool call (`getCardBalance`) served by a standalone second process, `mcp-card-server/`. `POST`/`DELETE /ingest/{tenantId}` manage per-tenant chunks in pgvector, idempotently. Two tenants (`acme`, `globex`) have distinct sample docs under `rag-docs/` and distinct seeded account/transaction rows. A `RagEvalTest` integration test scores retrieval precision@k and asserts cross-tenant isolation. Chat memory and the vector store share one Postgres instance (see Architecture below for why), and chat memory is itself tenant-namespaced (see "Tenant isolation" below) — not just the vector store.

Every `/chat` request now runs a fixed pipeline: **deterministic guardrails → router → scoped generation** (see "Router + guardrails + observability (M4)" below for the full writeup). Two guardrails run first, before any LLM call — a tenant-existence/status check (M4a) and a cross-tenant-reference check (M4d) — each denying with HTTP 403 and a generic reason if tripped. Past the guardrails, a separate structured-output LLM call (the router, M4b) classifies the message into RAG/MCP/BOTH/DENY and attaches only the matching capabilities to the actual generation call (scoped attachment) — RAG and MCP are no longer both always available by default, closing M3's "model doesn't always call the tool" gap. Every request is traceable end to end via a correlation id threaded through the whole pipeline (observability, M4c). Current milestone: **M5** (BOTH-path merge).

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

## Router + guardrails + observability (M4)

Every `/chat` request runs a fixed pipeline, in this order, before generation: **M4a tenant guardrail → M4d cross-tenant-reference guardrail → M4b router → scoped generation**, with M4c observability logging threaded through all of it.

### Two distinct denial types — keep them separate

- **Deterministic security denials (the guardrails, M4a + M4d).** No LLM involved. Thrown synchronously in `ChatController.chat()`, before any `Flux` is built, as a `ResponseStatusException(HttpStatus.FORBIDDEN, ...)` — Spring resolves this as a normal HTTP `403` with a JSON body (`{status, error, reason}`, via `ApiExceptionHandler`), not something that surfaces mid-stream. Both guardrails use a *generic* denial reason regardless of the specific cause (unknown vs. inactive tenant; which other tenant was referenced) — the specific cause only appears in the internal structured log, never in the HTTP-facing message, so the API surface doesn't leak enumeration-useful detail.
  - **M4a — tenant guardrail** (`TenantRegistryService.requireActiveTenant`): a single indexed lookup (`SELECT status FROM tenants WHERE tenant_id = ?`, PK lookup) denies unknown/inactive tenants.
  - **M4d — cross-tenant-reference guardrail** (`CrossTenantReferenceGuardrail.requireNoCrossTenantReference`): scans the message for any *other* registered tenant's name — case-insensitive, word-boundary regex match against the full `tenants` registry (`TenantRegistryService.listOtherTenantIds`), not general company-name NLP. Closes the M2-deferred narration gap: an `acme` caller asking "what is globex's spend limit" is now denied before generation, instead of getting acme's figure mislabeled as globex's. Only matches *registered* tenant names — a tenant whose name collides with common domain vocabulary (e.g. hypothetically "card") would cause false positives for every other tenant's messages; that's a tenant-naming/onboarding concern, not something this check can solve on its own.
- **The router's quality denial (M4b, `RoutingPath.DENY`).** An LLM classification outcome, not a security decision. Returns a normal streamed `200` response (the router's own one-sentence reason, as the entire response body) — generation is skipped, but this is not an HTTP error. Used for off-topic/nonsensical messages, never for tenant-related reasons (the router's system prompt explicitly says so, since tenant/access concerns are already handled by the guardrails above it).

### The router (M4b)

`PathRouter` is a separate, bare `ChatClient` (no default advisors — no chat memory, no RAG, no MCP tools) that classifies the user's raw message into `RAG`/`MCP`/`BOTH`/`DENY` via Spring AI structured output (`ChatClient.prompt().call().entity(RoutingDecision.class)`, a `BeanOutputConverter` under the hood) combined with Ollama's grammar-constrained `format: "json"` option and `temperature(0.0)` for reliable, deterministic parsing. If the structured-output call throws (model error or unparseable response), it fails open to `BOTH` — never to `DENY` — since `BOTH` is a superset of every other path's capabilities and a routing hiccup isn't grounds for a user-facing refusal.

**Scoped attachment is the actual mechanism, not just routing metadata.** Based on the router's decision, `ChatController` attaches *only* the matching capabilities to the generation `ChatClient` call: the tenant-scoped `QuestionAnswerAdvisor` for RAG/BOTH, the tenant-scoped MCP tool callbacks for MCP/BOTH, neither for DENY (which skips generation entirely). This is the real fix for M3's "model doesn't always choose to call the tool" limitation: an MCP-only request no longer has a RAG chunk sitting in the prompt to distract the model from calling the tool.

### Observability (M4c)

Every `/chat` request gets a correlation id (`UUID.randomUUID()`, generated at the top of `ChatController.chat()`) threaded as an **explicit method parameter** through the guardrails, the router, capability attachment, retrieval, and tool calls — deliberately not via `MDC`. The generation call's advisor chain runs on Reactor's `boundedElastic` scheduler, a different thread than the servlet thread that handled the guardrails/router, and a thread-local wouldn't reliably survive that handoff without depending on Reactor's automatic context propagation working exactly as expected; explicit parameter threading has no such dependency (verified live: the log's thread name visibly changes mid-request).

Structured (`key=value`, not string concatenation) logs at INFO cover the decision trail: guardrail outcomes (`event=guardrail_outcome`, with a `check=` field distinguishing `tenant_active` from `cross_tenant_reference`), router decision (`event=router_decision`), capabilities attached (`event=capabilities_attached`), retrieval summary + per-chunk id/tenantId/source/score (`event=retrieval_summary` / `event=retrieval_chunk`, via a custom `ChatObservabilityAdvisor`), and tool-call start/outcome including arguments (`event=tool_call_start` / `event=tool_call_outcome`, in `TenantScopedToolCallback`). Full chunk text and the exact final assembled prompt are logged too, but at DEBUG only (`event=retrieval_chunk_text` / `event=final_prompt`), gated behind `log.isDebugEnabled()` so the string-building cost isn't paid when DEBUG is off. Filtering by one correlation id isolates that request's complete trail from an otherwise-interleaved log.

`ChatObservabilityAdvisor` is a custom `BaseAdvisor` attached to every generation call (regardless of path) purely for logging — it never mutates the request or response. Its `getOrder()` is `Ordered.LOWEST_PRECEDENCE - 1`, deliberately **not** `Ordered.LOWEST_PRECEDENCE`: Spring AI's own terminal advisors (`ChatModelCallAdvisor` / `ChatModelStreamAdvisor`, the ones that actually invoke the model) are hardcoded to exactly `LOWEST_PRECEDENCE`, and using that same sentinel value silently prevented this advisor from ever running (found and fixed during M4c build-out via bytecode inspection plus empirical verification — worth remembering if a future advisor needs to run last in the chain).

**Maintain this as new layers are added.** Any new capability path, guardrail, or advisor added after M4 should log its outcome at INFO in the same `event=... correlationId=... tenantId=...` shape, and thread the correlation id through as an explicit parameter rather than reaching for `MDC`. The whole point of this layer is that a single request stays traceable end to end — a new code path that skips it breaks that guarantee.

### Known shortcomings (M4), not yet addressed

See `PLAN.md`'s M4 "Known shortcomings / deferred" section for the full list with rationale. In short: raw tool-call JSON can leak into the UI mid-prose on compound questions; response style is inconsistent/verbose; BOTH-path synthesis can conflate unrelated RAG/MCP facts (M5 fixes this); prompt-injection guarding of user content is not implemented; tenant *authentication* (verifying a caller is entitled to their `X-Tenant-Id`, as opposed to the guardrails' existence/status/content checks) is still not implemented, despite the original M4 plan saying it would close here.

## Milestones

Each milestone must be working, demoable, and committable to `main`, with its own validation gate (see `PLAN.md` §5 for full validation criteria per milestone):

- **M0** — Repo + skeleton: Spring Boot project scaffolded, README with architecture diagram, Ollama running locally.
- **M1** — Chat endpoint, streaming, memory: `/chat` → Ollama, SSE streaming, JDBC-backed chat memory, minimal HTML page.
- **M2** — RAG path + tenant isolation + minimal eval: ingestion, pgvector, `QuestionAnswerAdvisor`, tenant-scoped retrieval, config-externalized tuning, ingest endpoints, ~5 Q&A eval pairs scoring precision@k. **Repo flips to public here**, once `main` runs clean and isolation holds.
- **M3** — MCP path: one custom MCP server (a single mocked tool, e.g. card balance), Spring AI MCP client wiring, timeout/failure policy.
- **M4** — Router + guardrails + observability — **complete**: structured-output path router (RAG/MCP/BOTH/DENY + reason) with scoped-capability attachment; deterministic tenant guardrail (existence/status, M4a) and cross-tenant-reference guardrail (M4d) — both before the router, no LLM involved; full decision-trail logging via correlation id at INFO, plus verbose prompt/chunk-text logging at DEBUG (M4c). Prompt-injection guarding and tenant authentication were both originally in scope here but remain deferred — see "Known shortcomings (M4)" above and `PLAN.md`'s M4 section.
- **M5** — BOTH path + merge: BOTH-path *attachment* already exists (M4b attaches both the RAG advisor and MCP tools to one generation call); M5 adds the explicit merge/synthesis step — current BOTH answers can conflate unrelated RAG/MCP facts — plus degrade-to-single-path on failure.
- **M6** — RAG tuning pass: sweep chunk size/overlap/top-K/threshold/embedding model against the eval set, add hybrid retrieval (vector + Postgres full-text), document before/after precision@k tradeoffs.

## Working conventions

- Ship sequentially: bias toward demoable progress at each step over completeness. Every milestone should be runnable and committable to `main`.
- Experimental thrash happens on branches; `main` always runs clean.
- Commits are meaningful — no `TODO: fix hack` on `main`.
- The eval harness (M2) and the tuning comparison table (M6) are first-class deliverables, not incidental artifacts — they're the strongest evidence of deliberate retrieval-tuning work.
- README framing: state outcomes as capability (what the system does, what was built) — never as a learning/practice project.
