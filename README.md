# agentic-rag-mcp-assistant

A Spring AI agentic assistant for a fintech context that routes each request through the right
retrieval strategy — internal knowledge via RAG, live data via MCP, both, or a guarded refusal —
with real per-tenant data isolation. Built entirely on a free/local model stack (Ollama); no paid
API dependency.

See `PLAN.md` for the full architecture, locked scope decisions, and milestone plan.

## Status

**M2 complete — RAG with real tenant isolation.** `POST /chat` is retrieval-grounded and
tenant-scoped: it embeds the question, retrieves the top-K most similar chunks from Postgres/
pgvector filtered to the requesting tenant, and injects them as context via Spring AI's
`QuestionAnswerAdvisor` before generating — on top of the streaming SSE response and JDBC-backed
chat memory from M1. Two tenants (`acme`, `globex`) have distinct sample docs demonstrating
isolation. A minimal eval harness scores retrieval precision@k and asserts cross-tenant isolation
holds. The MCP path, router, and guardrails land in later milestones (see `PLAN.md` Section 5).

## Stack

- Java 21, Spring Boot 3.5.16, Spring AI 1.0.9
- Ollama: `llama3.1:8b` (chat), `nomic-embed-text` (embeddings)
- Postgres + pgvector (Docker) — shared by chat memory and the RAG vector store

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
./mvnw spring-boot:run
```

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

**Deferred to M4 (guardrails), stated honestly:**

- **No tenant authentication yet.** Nothing verifies a caller is entitled to the `X-Tenant-Id` they
  send — any caller can claim any tenant today. This is an authorization gap, not a filter-bypass
  bug, and it closes in M4.
- **The generation step can still narrate about another named tenant using in-tenant data.** If an
  `acme` caller asks "what is globex's spend limit," retrieval correctly returns *acme's* chunks,
  but the model may attach acme's figure to the "globex" label the prompt introduced. No data
  crosses tenants — the retrieved chunk is acme's own — but the narration is misleading. Guarding
  cross-tenant *requests* at the input layer is M4 work.

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
