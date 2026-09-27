# Data Ownership

Relay currently runs as one Spring Boot app against one SQLite file
(`~/.relay-orchestrator/index.db`). Table prefixes encode the
future microservice boundaries so the eventual split is mechanical.

## Orchestrator Service

Owns the story lifecycle: sessions, plans, approvals, diffs.

| Table                      | Purpose                                                           |
| -------------------------- | ----------------------------------------------------------------- |
| `orch_pipeline_sessions`   | Story state, Q&A history, clarifier result, implementation result |
| `orch_artifacts` (planned) | Plans, RCAs, designs, reviews                                     |

## Indexer Service

Owns the code index: parsing, chunking, BM25 retrieval.

| Table              | Purpose                      |
| ------------------ | ---------------------------- |
| `idx_classes`      | Parsed class declarations    |
| `idx_methods`      | Parsed method declarations   |
| `idx_dependencies` | Cross-class dependency edges |
| `idx_code_chunks`  | Chunks for BM25 retrieval    |

## Telemetry Service

Owns token and cost accounting.

| Table                | Purpose              |
| -------------------- | -------------------- |
| `tele_token_metrics` | Per-LLM-call metrics |

## Conventions

1. **New tables always get a service prefix** — `orch_`, `idx_`, `tele_`, `git_`.
2. **New tables carry `service_id TEXT NOT NULL DEFAULT 'default'`** if they hold per-service data.
3. **Cross-service references are by ID, not foreign key.** No JOINs across ownership boundaries.
4. **When the split happens**, grep for the prefix and copy the tables to a new DB.

## Migration path

- **Now → Phase 3** — one Spring Boot app, one SQLite file, prefixed tables.
- **Phase 3 → multi-repo** — filter queries by `service_id`.
- **Multi-user** — swap SQLite for Postgres, same schema.
- **Multi-process** — split `idx_*` into an Indexer service, `orch_*` into an Orchestrator, `tele_*` into Telemetry.
