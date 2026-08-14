# agentic-rag-mcp-assistant

A Spring AI agentic assistant for a fintech context that routes each request through the right
retrieval strategy — internal knowledge via RAG, live data via MCP, both, or a guarded refusal —
with real per-tenant data isolation. Built entirely on a free/local model stack (Ollama); no paid
API dependency.

See `PLAN.md` for the full architecture, locked scope decisions, and milestone plan.

## Status

**M4 complete — router, guardrails, and full request-trace observability, on top of M1–M3's
chat/RAG/MCP foundation.**
Every `/chat` request now passes through two deterministic guardrails before any LLM call: a
tenant-registry check (unknown or deprovisioned tenants denied via a single indexed lookup) and a
cross-tenant-reference check (denies a message that names a *different* known tenant by name).
The second one closes a gap carried since M2: retrieval was always tenant-scoped, but the model
could still narrate about another named tenant using the caller's own data if the prompt invited
it (e.g. an acme caller asking "what is globex's spend limit" got acme's figure mislabeled as
globex's) — that's now refused before generation starts, not policed after the fact. Past the
guardrails, a separate, focused LLM call (the router) classifies each message into
RAG/MCP/BOTH/DENY and attaches *only* the matching capabilities to the actual generation call —
this is what fixes M3's "model doesn't always call the tool" ambiguity, since an MCP-only request
no longer has a distracting RAG chunk sitting in its prompt. The router's own DENY is a
quality/off-topic refusal (a normal 200 response, not an HTTP error) — a different, unrelated code
path from the guardrails' security denials (403s). Every request is traceable end to end via a
correlation id; see "Observability" below.

**Still open, documented honestly, not silently dropped:** tenant *authentication* (verifying a
caller is actually entitled to the `X-Tenant-Id` they claim) and prompt-injection guarding of user
content are both deferred — see `PLAN.md`'s M4 "Known shortcomings" section for the full list and
rationale.

## Stack

- Java 21, Spring Boot 3.5.16, Spring AI 1.0.9
- Ollama: `llama3.1:8b` (chat), `nomic-embed-text` (embeddings)
- Postgres + pgvector (Docker) — shared by chat memory, the RAG vector store, and (in a separate
  process) the MCP server's `accounts`/`transactions` tables
- MCP: one custom server (`mcp-card-server/`, HTTP+SSE transport, its own Spring Boot process)

## How to run

**Prerequisites:**
- Java 21
- [Docker](https://www.docker.com) (for Postgres + pgvector)
- [Ollama](https://ollama.com) running locally with the required models pulled:
  ```
  ollama pull llama3.1:8b
  ollama pull nomic-embed-text
  ```

**Run:**
```
docker compose up -d
cd mcp-card-server && ./mvnw spring-boot:run   # separate process, port 8081
```
In another terminal:
```
./mvnw spring-boot:run                          # main app, port 8080
```
The card server isn't required for the app to start or for the RAG path to work — see "MCP
path" below for what happens if it's down.

The app starts on `http://localhost:8080`. Verify with:
```
curl http://localhost:8080/health
```

Ingest each tenant's documents (folder-per-tenant: `rag-docs/<tenantId>/`), then ask a
tenant-scoped question. The tenant is carried on `/chat` as the `X-Tenant-Id` header:
```
curl -X POST http://localhost:8080/ingest/acme
curl -X POST http://localhost:8080/ingest/globex

curl -N -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" -H "X-Tenant-Id: acme" \
  -d '{"message":"What is the per-diem limit for international travel?","conversationId":"demo"}'
# -> $110/day (Acme's policy)

curl -N -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" -H "X-Tenant-Id: globex" \
  -d '{"message":"What is the per-diem limit for international travel?","conversationId":"demo"}'
# -> $150/day (Globex's policy) -- same question, same conversationId, correctly different
# tenant-scoped answer. Retrieval and memory are isolated at the data layer (see below).
```
`/ingest/{tenantId}` is idempotent — re-running it re-embeds changed files without creating
duplicate chunks (deterministic chunk IDs derived from tenant + source path + chunk index).
`DELETE /ingest/{tenantId}` removes only that tenant's chunks; `tenantId` is required, there is no
delete-everything call.

Ask a question that needs the live MCP tool instead (same tenant header, no ingestion needed —
the card server seeds its own sample data on startup):
```
curl -N -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" -H "X-Tenant-Id: acme" \
  -d '{"message":"What is the current balance on card CARD-1001?","conversationId":"demo2"}'
# -> $4,250.75 USD

curl -N -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" -H "X-Tenant-Id: globex" \
  -d '{"message":"What is the current balance on card CARD-1001?","conversationId":"demo2"}'
# -> $12,500.00 USD -- same account id, different tenant, correctly different (and isolated) balance
```

Check what MCP tools the app currently sees with `GET /mcp/tools` — a read-only diagnostic endpoint,
not tenant-scoped (it shows each tool's real schema, including the `tenantId` parameter that
`/chat` strips from what the model sees):
```
curl http://localhost:8080/mcp/tools
# {"mcpServerAvailable":true,"tools":[{"name":"...getCardBalance","description":"...","inputSchema":{...}}]}
```
If the card server is down, this returns HTTP 200 with `{"mcpServerAvailable":false,"tools":[]}` —
never an error, by design.

Open `http://localhost:8080` in a browser for a minimal chat UI — it has a tenant field (default
`acme`) alongside the message box.

**Retrieval eval harness** (~5 Q&A pairs across both tenants, plus a dedicated cross-tenant
isolation check) — requires Postgres and Ollama running; it ingests both tenants itself:
```
./mvnw test -Dtest=RagEvalTest
```
Prints `precision@4 = 5/5 = 1.00` and confirms the two tenants' retrieved chunk sets are disjoint
for the same query.

`conversationId` is optional (defaults to a single implicit conversation); pass a stable value to
keep multi-turn context. Chat memory is stored in the same Postgres instance as the vector store
(JDBC-backed `ChatMemory`), and is itself namespaced by tenant (see implementation notes below) so
it can't leak across tenants either.

## Tenant isolation — what holds and what's deferred

Isolation is enforced at the **data layer** and is precise about its current boundaries — worth
stating exactly rather than as a blanket claim:

- **Retrieval is tenant-scoped and cannot be bypassed through the retrieval path.** The tenant
  filter is built as a `Filter.Expression` AST directly from the `X-Tenant-Id` header in Java
  (`FilterExpressionBuilder().eq("tenantId", tenantId)`) — never as filter text — and a fresh,
  tenant-scoped `QuestionAnswerAdvisor` is constructed per request rather than shared, so one
  request's filter can never apply to another's. `QuestionAnswerAdvisor` does have a string-based
  filter-override path (`qa_filter_expression` context key, parsed at call time) that could
  theoretically bypass a *shared* filter — this code never populates that key, so it's unreachable
  here. The filter is ANDed directly into pgvector's SQL `WHERE` clause (not a post-filter on
  results), so the database itself never returns another tenant's rows.
- **Chat memory is tenant-namespaced** (see implementation notes), so conversation history can't
  bleed across tenants even when two requests reuse the same `conversationId`.

**Closed in M4, and one gap still open — stated honestly:**

- **Cross-tenant narration is now blocked.** The gap that used to live here — an `acme` caller
  asking "what is globex's spend limit" getting acme's figure mislabeled as globex's — is closed
  by a deterministic guardrail: any message naming a *different* known tenant is denied (403,
  generic reason) before the router or any LLM call runs. No data ever crossed tenants even before
  this — the retrieved chunk was always acme's own — but the misleading narration is now refused
  outright instead of merely being possible to produce.
- **No tenant authentication yet.** Nothing verifies a caller is entitled to the `X-Tenant-Id` they
  send — any caller can still claim any tenant. This is an authorization gap, not a filter-bypass
  bug. It did **not** close in M4 as originally planned — the M4 guardrails check tenant
  existence, status, and message content, never caller identity. Still open; documented in
  `PLAN.md`'s M4 "Known shortcomings" section.

## MCP path

**Transport: HTTP+SSE, not stdio.** Spring AI's MCP client supports both. stdio means the client
spawns the server as a child process and talks over its stdin/stdout — convenient for CLI-hosted
tools, but it couples the server's lifecycle to the client's and makes "kill the server
independently while the client keeps running" awkward to demonstrate (you'd be killing a child
process of the very app you're testing). HTTP+SSE makes the server a genuinely separate,
independently-deployable process with its own port — you can `kill -9` it, `curl` it directly, or
run it on a different machine, and the validation scenario ("killing the MCP server mid-request
degrades gracefully") is literally just killing an unrelated PID.

**Server and client are two separate Spring Boot processes,** not two modules of one build and
not a Maven multi-module reactor. `mcp-card-server/` has its own `pom.xml`, own `mvnw`, own
port (8081), and depends on nothing from the main app. This keeps the main app's build untouched
aside from adding the MCP *client* dependency, and mirrors how a real internal tool-service would
actually be deployed. Both processes point at the same Postgres instance (`docker-compose.yml`)
but own disjoint tables — `vector_store`/`SPRING_AI_CHAT_MEMORY` for the main app, `accounts`/
`transactions` for the card server — reusing infrastructure without sharing schema ownership.

**Tool registration and discovery.** On the server, `CardBalanceTools.getCardBalance(accountId,
tenantId)` is a plain `@Tool`-annotated method wrapped in a `MethodToolCallbackProvider` bean;
`spring-ai-starter-mcp-server-webmvc`'s autoconfiguration picks up any `ToolCallbackProvider`/
`ToolCallback` bean in the context and exposes it over the MCP protocol automatically — no manual
JSON-RPC wiring. On the client, discovery is a live `list_tools` call
(`SyncMcpToolCallbackProvider.getToolCallbacks()`) — see "Graceful degradation" below for why that
matters.

**Tenant scoping on the tool call — same principle as the RAG filter, applied to tools.** The
`getCardBalance` tool's real schema (as registered on the server) has both `accountId` and
`tenantId` parameters, because the server needs `tenantId` to scope its SQL query. But the model
never sees or supplies `tenantId`: `TenantScopedToolCallback` (built fresh per request in
`ChatController`, from the trusted `X-Tenant-Id` header — exactly like the per-request
`QuestionAnswerAdvisor`) does two things to every MCP-discovered callback before handing it to the
model:
1. Strips the `tenantId` property out of the JSON schema the model is shown, so there's nothing
   for the model to fill in or for a prompt-injection attempt to target.
2. On every call, parses the model's JSON arguments and force-overwrites `tenantId` with the
   trusted value — not just filling a gap, overwriting anything present — before forwarding to the
   real MCP call.

Verified directly: asking for `CARD-1001`'s balance as `acme` and then as `globex` (same
`conversationId`) returns $4,250.75 and $12,500.00 respectively — the same account id, correctly
isolated, because the tenant came from the header on each request, never from the model.

**Graceful degradation — two distinct failure modes, both tested by actually killing the process:**
- **MCP server down when the main app starts.** This surfaced a real gap during testing: Spring
  AI's auto-registered `ToolCallbackProvider` bean, merely by existing in the context, makes an
  *unrelated* bean elsewhere (`ToolCallingAutoConfiguration`'s `toolCallbackResolver`, built while
  wiring the Ollama chat model) eagerly call `list_tools` during `ApplicationContext` startup —
  independent of `spring.ai.mcp.client.initialized` and independent of `@Lazy` on any of *our* own
  injection points, since the eager caller isn't code we wrote. With the card server down, this
  failed the *entire app's startup* (`Client failed to initialize listing tools`, several
  autoconfiguration frames deep). Fixed by disabling that auto-registered bean entirely
  (`spring.ai.mcp.client.toolcallback.enabled=false`) and having `ChatController` build its own
  `SyncMcpToolCallbackProvider` from the raw `McpSyncClient` list, per request, inside its own
  try/catch. Confirmed fixed with a real cold-start test (card server killed, main app started
  fresh): starts cleanly, and a chat request degrades to "no live-data tools this turn" instead.
- **MCP server killed mid-conversation** (up when the request starts, dead by the time the tool is
  called). `TenantScopedToolCallback.call()` wraps the delegate call in a try/catch and always
  returns a clean, non-throwing error string on any failure — connection refused, the 5-second
  `request-timeout`, or a protocol error. Confirmed with a real test: card server killed via its
  actual PID, main app still running, next request for the same tool completed in ~5s (the
  configured timeout) with a coherent natural-language response and no stack trace, no HTTP 500,
  and the main app process untouched.

**An honest limitation, not a bug: the model doesn't always choose to call the tool.**
`llama3.1:8b`'s tool-calling is a model decision, not something this code forces. Observed while
testing: if `QuestionAnswerAdvisor` happens to retrieve a tangentially related RAG chunk (e.g. the
card program guide, for a balance question), its prompt template's strict "answer using only the
provided context" framing sometimes leads the model to decline to call the tool at all, even
though the tool was correctly offered and would have worked — it did in other, more clear-cut
runs of the identical question. This is precisely the ambiguity M4's structured-output router
resolves, by attaching MCP tools without a competing RAG advisor for MCP-only requests (see
"Status" above) — M3's job was only to prove the tool *can* be called correctly and safely when
invoked, which it repeatedly was.

## Observability

Every `/chat` request is traceable end to end via a single correlation id (a UUID generated per
request), threaded explicitly as a method parameter through the guardrail checks, the router,
retrieval, and any tool call — not via a thread-local (`MDC`), since the actual generation call's
advisor chain runs on a different thread (Reactor's `boundedElastic` scheduler) than the servlet
thread that handled the guardrails and router, and a thread-local wouldn't reliably survive that
handoff. Structured (`key=value`, not string concatenation) logs at INFO capture the full decision
trail:

- **Guardrail outcomes** — which check ran (`tenant_active` / `cross_tenant_reference`), allowed
  or denied, and why.
- **Router decision** — the chosen path (RAG/MCP/BOTH/DENY) and the router's own one-sentence
  reason.
- **Capabilities attached** — which advisors/tools were actually attached, based on the router's
  decision.
- **Retrieval details** (when RAG ran) — retrieved chunk ids, their `tenantId`, source file, and
  similarity score, so tenant-scoping is verifiable straight from the log, without enabling
  verbose logging.
- **Tool calls** (when MCP ran) — which tool, its arguments (tenant included), and
  success/failure/timeout outcome.

Full chunk text and the exact final prompt sent to the model are logged too, but gated behind
DEBUG — verbose by design, off by default (`--logging.level.com.rupeshagrahari.agenticrag=DEBUG`
to enable). Filtering a log by one correlation id greps out that single request's complete trail
from an otherwise-interleaved log, so when an answer is wrong, it's possible to tell whether it
was a guardrail decision, a routing choice, retrieval, or generation.

## Implementation notes

- `PgVectorStoreAutoConfiguration` binds to Spring Boot's single primary `DataSource` — it has no
  independent connection config of its own. That's why chat memory, introduced in M1 on a
  standalone H2 file database, now shares the same Postgres instance as the vector store: once RAG
  needed Postgres, there was no way to keep a second, separate datastore for memory without a
  manually wired second `DataSource`. This is a net simplification — Postgres is a first-class
  supported dialect for Spring AI's JDBC chat memory (unlike H2, which needed a schema-borrowing
  workaround in M1).
- Spring AI's `TokenTextSplitter` (1.0.9) has no chunk-overlap parameter — chunks are back-to-back,
  not sliding-window. Only chunk size is exposed as a tuning knob (`rag.ingestion.chunk-size`).
- **Chat memory is namespaced by tenant, not just `conversationId`.** `conversationId` is a
  client-chosen value (a browser-tab UUID); without tenant-namespacing, two requests reusing the
  same `conversationId` under different tenants would read/write the same memory row — a leak the
  vector-store filter doesn't touch at all. The actual `ChatMemory.CONVERSATION_ID` used internally
  is `UUID.nameUUIDFromBytes(tenantId + "::" + conversationId)` — hashed into a UUID rather than
  stored as the literal concatenation, because Spring AI's shipped chat-memory schema defines
  `conversation_id VARCHAR(36)`, sized for a raw UUID; the plain-concatenation version overflowed
  that column as soon as `tenantId` was non-trivial (`DataIntegrityViolationException: value too
  long for type character varying(36)`), caught when a real question hit it in the running app.
  Verified directly: same `conversationId` under `acme` then `globex` — zero memory bleed-through
  in either direction, memory still intact within a single tenant.

## Future work

Documented here as scope is intentionally deferred, not because it's unplanned:

- **API-driven ingestion** (`POST /tenants/{id}/documents`) and source connectors (S3/Drive/Confluence)
  behind the same `ingest(tenantId, source)` seam.
- **Incremental/event-driven sync** (CDC/outbox) so re-embedding only touches changed chunks.
- **Spring Boot 4 / Spring AI 2.0 migration.** M0 deliberately targets Spring Boot 3.5.16 / Spring AI
  1.0.9 for ecosystem maturity (docs, examples, community MCP/RAG starters) over the newest API — a
  reasonable tradeoff for a portfolio project, not for a production deployment.
