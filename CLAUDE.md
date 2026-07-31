# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This repo currently contains only planning artifacts (`PLAN.md`, `README.md`) — no Spring Boot project has been scaffolded yet (no `pom.xml`/`mvnw`, no `src/`). The first work in this repo is milestone **M0** below. Once the project is scaffolded, update this file with the real build/lint/test commands (`./mvnw spring-boot:run`, `./mvnw test`, etc.) and remove this note.

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
- Ollama: a chat model (e.g. `llama3.1` or `qwen2.5`) + an embedding model (e.g. `nomic-embed-text`)
- Postgres + pgvector (`spring-ai-pgvector-store` starter)
- Chat memory: JDBC-backed `ChatMemory` (survives restarts)
- UI: single static HTML page hitting `/chat` over SSE — no frontend framework

### Config externalization
All RAG tuning knobs (chunk size, chunk overlap, top-K, similarity threshold, embedding model name) belong in `application.yml`, never hardcoded. "Tuning" must never mean "recompile."

## Ingestion design (the extensibility seam)

**Entry point:** `IngestionService.ingest(tenantId, source)` — this signature is what keeps future flexibility; onboarding a tenant later means calling this with a new tenant + source, not writing new code.

- `POST /ingest/{tenantId}` → scans `rag-docs/<tenantId>/`, chunks, embeds, upserts every chunk tagged with `tenantId`. Idempotent (re-running picks up new/changed files, no duplicates).
- `DELETE /ingest/{tenantId}` → removes all chunks for that tenant. **`tenantId` is required** — there is no delete-everything call.
- **Idempotency:** deterministic chunk id = `hash(tenantId + relative source path + chunk index)`, upsert semantics.
- **Provisioning is data, not schema:** shared vector store, `tenantId` as metadata — no new table/migration per tenant.

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
