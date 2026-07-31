# agentic-rag-mcp-assistant

A Spring AI agentic assistant for a fintech context that routes each request through the right
retrieval strategy — internal knowledge via RAG, live data via MCP, both, or a guarded refusal —
with real per-tenant data isolation. Built entirely on a free/local model stack (Ollama); no paid
API dependency.

See `PLAN.md` for the full architecture, locked scope decisions, and milestone plan.

## Status

**M0 — repo + skeleton.** Spring Boot project scaffolded with a single `/health` endpoint. The
chat endpoint, RAG path, MCP path, router, and guardrails land in later milestones (see `PLAN.md`
Section 5).

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

## Future work

Documented here as scope is intentionally deferred, not because it's unplanned:

- **API-driven ingestion** (`POST /tenants/{id}/documents`) and source connectors (S3/Drive/Confluence)
  behind the same `ingest(tenantId, source)` seam.
- **Incremental/event-driven sync** (CDC/outbox) so re-embedding only touches changed chunks.
- **Spring Boot 4 / Spring AI 2.0 migration.** M0 deliberately targets Spring Boot 3.5.16 / Spring AI
  1.0.9 for ecosystem maturity (docs, examples, community MCP/RAG starters) over the newest API — a
  reasonable tradeoff for a portfolio project, not for a production deployment.
