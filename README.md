# agentic-rag-mcp-assistant

A Spring AI agentic assistant for a fintech context that routes each request through the right
retrieval strategy — internal knowledge via RAG, live data via MCP, both, or a guarded refusal —
with real per-tenant data isolation. Built entirely on a free/local model stack (Ollama); no paid
API dependency.

See `PLAN.md` for the full architecture, locked scope decisions, and milestone plan.

## Status

**M1 — streaming chat with persistent memory.** `POST /chat` streams token-by-token responses
from Ollama over SSE, with JDBC-backed (H2, file-based) chat memory that survives app restarts,
and a minimal browser UI at `/`. The RAG path, MCP path, router, and guardrails land in later
milestones (see `PLAN.md` Section 5).

## Stack

- Java 21, Spring Boot 3.5.16, Spring AI 1.0.9
- Ollama: `llama3.1` (chat), `nomic-embed-text` (embeddings)

## How to run

**Prerequisites:**
- Java 21
- [Ollama](https://ollama.com) running locally with the required models pulled:
  ```
  ollama pull llama3.1
  ollama pull nomic-embed-text
  ```

**Run:**
```
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`. Verify with:
```
curl http://localhost:8080/health
```

Open `http://localhost:8080` in a browser for a minimal chat UI, or call the streaming endpoint
directly:
```
curl -N -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello, who are you?","conversationId":"demo"}'
```
`conversationId` is optional (defaults to a single implicit conversation); pass a stable value to
keep multi-turn context, including across an app restart — chat memory is stored in a local,
file-based H2 database (`./data/chatmemory.mv.db`, gitignored).

**Implementation note:** Spring AI 1.0.9's JDBC chat-memory module ships schema scripts for
Postgres/MySQL/SQL Server/HSQLDB but not H2; its dialect auto-detection also falls back to the
Postgres dialect for H2. Since that dialect's SQL is plain ANSI and H2-compatible, `application.yml`
points schema initialization at the bundled Postgres script (`spring.ai.chat.memory.repository.jdbc.platform: postgresql`)
rather than hand-writing a duplicate schema file.

## Future work

Documented here as scope is intentionally deferred, not because it's unplanned:

- **API-driven ingestion** (`POST /tenants/{id}/documents`) and source connectors (S3/Drive/Confluence)
  behind the same `ingest(tenantId, source)` seam.
- **Incremental/event-driven sync** (CDC/outbox) so re-embedding only touches changed chunks.
- **Spring Boot 4 / Spring AI 2.0 migration.** M0 deliberately targets Spring Boot 3.5.16 / Spring AI
  1.0.9 for ecosystem maturity (docs, examples, community MCP/RAG starters) over the newest API — a
  reasonable tradeoff for a portfolio project, not for a production deployment.
