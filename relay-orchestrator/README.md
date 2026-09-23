# Relay Orchestrator

A local, single-user code orchestration pipeline. It reads a feature story,
asks clarifying questions, grounds its analysis in your actual codebase via
local embeddings, and produces a structured intent report. Implementation,
testing, and PR creation come in later phases.

## Table of contents

1. [What works today](#what-works-today)
2. [Architecture](#architecture)
3. [Phases completed](#phases-completed)
4. [Token reduction strategy](#token-reduction-strategy)
5. [How to read logs](#how-to-read-logs)
6. [How to tell the cache is working](#how-to-tell-the-cache-is-working)
7. [Database and config](#database-and-config)
8. [Run it](#run-it)
9. [Verification tests](#verification-tests)
10. [What is intentionally not built yet](#what-is-intentionally-not-built-yet)
11. [Console and page inventory](#console-and-page-inventory)
12. [Limitations](#limitations)

---

## What works today

- **Provider-agnostic LLM layer.** Anthropic is fully wired; Copilot is a
  labelled stub. One config field switches providers.
- **Language-agnostic code index.** JavaParser is wrapped behind a
  `LanguageSupport` interface. Adding a new language is a new class, not a
  rewrite.
- **Multi-turn clarification loop.** Sessions persist in SQLite. A story
  moves through `AWAITING_ANSWERS` → `READY_TO_IMPLEMENT` (or `BLOCKED`,
  `CAPPED`, `ERROR`). Hard cap at 3 turns.
- **Tool-use structured output.** The clarifier forces the model to call
  `submit_clarification_report` with a JSON schema. No regex parsing, no
  format drift.
- **Local embeddings + retrieval.** ONNX Runtime runs MiniLM-L6-v2 on the
  user's CPU. Code chunks are embedded and stored as vectors in SQLite.
  Retrieval runs cosine similarity against the story to pick relevant
  chunks. No external embedding API. No cost per query.
- **Prompt caching.** Stable prefixes (system prompt + tool schema +
  retrieval context) are marked cacheable. Anthropic bills repeat reads at
  ~10% of normal input rate. This activates automatically once the cached
  prefix crosses 1024 tokens — which retrieval now guarantees.
- **Session token budget.** Warn at 80%, block at 100% of a per-session
  token or cost cap. Prevents runaway loops.
- **Live SSE log tail.** Every log event streams to the browser. Two
  audiences: developers see SLF4J stack traces in the IDE console, users
  see a curated stream in the browser.
- **Persona system.** Michael (orchestrator) is bundled. Adding a new
  persona is a markdown folder, no recompile.

## Architecture

```
com.relay.orchestrator
├── agent/          Persona loading, role → persona mapping
├── config/         AppConfigManager, RepositoryConfig, YAML persistence
├── connection/     Provider checkers, ConnectionController (HTTP endpoints)
├── index/          RepoIndexerService, IndexRepository (SQLite CRUD)
├── lang/           LanguageSupport interface + LanguageRegistry
│   └── java/       JavaLanguageSupport + JavaCodeParser
├── llm/            LlmClient interface, router, Anthropic client, Copilot stub
├── logging/        LogBroadcaster (SSE), LogEvent, TokenTrackerService
├── pipeline/       PipelineSession, PipelineSessionStore, ClarificationLoopService
├── retrieval/      ModelProvisioner, EmbeddingService, ChunkBuilder,
│                   ChunkRepository, RetrievalService
├── service/        IntentClarifierService, ClarificationResult
└── tokens/         TokenMetricsService, SessionBudgetService
```

**Key rules:**

- No class outside `llm/` may import Anthropic or Copilot SDK types.
  Call `LlmClientRouter` instead.
- No class outside `lang/` may know about a specific language. Route
  through `LanguageRegistry`.
- No class outside `retrieval/` should manipulate vectors directly. Ask
  `RetrievalService`.

## Phases completed

| Phase   | Deliverable                                                                       | Key files                                                                                          |
| ------- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| **0**   | Security cleanup, config unification, provider abstraction, SSE noise suppression | `AppConfigManager`, `LlmClient`, `SseDisconnectLogFilter`                                          |
| **1.0** | Provider-agnostic LLM layer with Anthropic + Copilot stub                         | `LlmClient`, `LlmClientRouter`, `AnthropicLlmClient`                                               |
| **1.1** | Language-agnostic code model                                                      | `LanguageSupport`, `LanguageRegistry`, `JavaCodeParser`                                            |
| **1.2** | Clarifier through router; personas; tool-use; multi-turn loop; live UI            | `AgentRegistry`, `IntentClarifierService`, `ClarificationLoopService`, `pipeline-stages.html`      |
| **1.3** | Local embeddings, chunking, retrieval, prompt caching, session budget             | `ModelProvisioner`, `EmbeddingService`, `ChunkBuilder`, `RetrievalService`, `SessionBudgetService` |

**Not yet built:** implementer (writes code), tester (runs tests), Git/PR
integration, native packaging, real Copilot client. See [What is
intentionally not built yet](#what-is-intentionally-not-built-yet).

## Token reduction strategy

Six mechanisms, layered. Each one can save money on a different axis.

| #   | Mechanism                    | Where it lives                                  | Savings                                   |
| --- | ---------------------------- | ----------------------------------------------- | ----------------------------------------- |
| 1   | **Deterministic-first**      | Everywhere — no LLM call when code can decide   | Up to 60% of potential calls never happen |
| 2   | **Model selection per role** | `config.yml` → `agents.overrides`               | ~70% on clarifier cost (Haiku vs Sonnet)  |
| 3   | **Prompt caching**           | `AnthropicLlmClient.buildRequestBody`           | ~70% of input tokens on repeat calls      |
| 4   | **Retrieval trimming**       | `RetrievalService` (top-K + floor + token cap)  | ~90% of context tokens vs naive retrieval |
| 5   | **Session budget**           | `SessionBudgetService`                          | Prevents runaway; block at cap            |
| 6   | **Tool-use schemas**         | `IntentClarifierService.buildClarificationTool` | Eliminates re-asks from malformed output  |

### How prompt caching actually works

Two blocks are marked cacheable in every Anthropic request:

1. The system prompt (persona + tool guidance + retrieval guidance)
2. The last tool definition in the `tools[]` array

Anthropic caches everything up to and including those markers for 5
minutes. Subsequent calls within that window bill the cached prefix at 10%
of the normal input rate.

**The catch:** the cached prefix must be ≥1024 tokens. Early in the
project, our persona + tool schema was ~850 tokens — below the minimum.
Retrieval context added ~2000 tokens, pushing the prefix to ~3000. That
crossed the threshold, and caching activated.

**What you save:** on a call with 3000 cached tokens and 900 fresh tokens,
you pay for 900 at full rate and 3000 at 10% rate — effectively saving
~65% of what the full input would have cost.

### How retrieval trimming works

`RetrievalService.retrieveForStory()` runs five steps:

```
1. Embed the story → 384-dim query vector (local, free)
2. Load all indexed chunks from SQLite
3. Score each with cosine similarity (dot product of normalized vectors)
4. Drop anything below the similarity floor (default 0.20)
5. Keep top-K (default 5), honoring a 2000-token context cap
```

The floor rejects noise; top-K bounds size; the token cap prevents an
oversized chunk from dominating context.

**Tuning the floor:** if retrieval returns nothing for stories you expect
to match, run `/debug/retrieve?story=...` and inspect `top10NoFloor`. If
the top score is just below the floor, lower the floor in
`RetrievalService`.

## How to read logs

Two log channels, two audiences.

### Channel 1 — IDE console (SLF4J)

**What you see:** every log line with timestamp, thread name, level,
class, and message. Stack traces when exceptions occur.

**Example:**

```
2026-09-22T23:40:42.87-04:00  INFO 3480 --- [relay-orchestrator] [nio-8081-exec-4]
    c.r.o.retrieval.RetrievalService : Retrieval: 139 candidates, 5 above floor 0.20, kept 5 (~780 tokens)
```

**Look here when:** debugging, investigating crashes, tuning retrieval
scores, checking the actual HTTP request to Anthropic.

**How to enable DEBUG:** add to `application.yml`:

```yaml
logging:
  level:
    com.relay.orchestrator: DEBUG
```

### Channel 2 — Browser "Live Slicer Stream" (SSE)

**What you see:** a curated, user-friendly stream of the same events minus
stack traces. Delivered via Server-Sent Events (SSE) to the bottom panel
of every page.

**Example (matches the console line above):**

```
23:40:42.87 [INFO] Retrieval: 139 candidates, 5 above floor 0.20, kept 5 (~780 tokens)
23:40:42.87 [INFO] [RETRIEVAL] Retrieved 5 chunks: com.relay.service.PipelineOrchestrator.waitForApproval, ...
23:40:42.87 [INFO] [Michael Scott] Analyzing story: "recommend pipeline mode"
```

**Look here when:** watching a pipeline run, tracking progress, sharing
demos with a screen recording.

**Log prefix reference:**

| Prefix            | Meaning                                  |
| ----------------- | ---------------------------------------- |
| `[LOOP]`          | Pipeline loop state transitions          |
| `[RETRIEVAL]`     | Chunks selected by the retrieval service |
| `[Michael Scott]` | Clarifier LLM call and response          |
| `[CACHE]`         | Prompt cache events                      |
| `[BUDGET]`        | Session token budget warnings and blocks |
| `[CLARIFIER]`     | Legacy clarifier events                  |

**Typical successful run:**

```
[LOOP] Starting session f6903175
[RETRIEVAL] Retrieved 5 chunks: PipelineOrchestrator.waitForApproval, ...
[Michael Scott] Analyzing story: "..."
[SUCCESS] [Michael Scott] state=NEEDS_INPUT confidence=MEDIUM complexity=low in=892 out=663 cacheRead=0
[LOOP] Session f6903175 → stage=AWAITING_ANSWERS state=NEEDS_INPUT turn=1/3
```

Read `in=`, `out=`, and `cacheRead=` on the `[SUCCESS]` line:

- `in=` = fresh input tokens (billed at full rate)
- `out=` = output tokens
- `cacheRead=` = tokens read from Anthropic's prompt cache (billed at 10%)

## How to tell the cache is working

Three places to look.

### 1. In the log

The `[SUCCESS]` line prints `cacheRead=`:

```
[SUCCESS] [Michael Scott] state=NEEDS_INPUT in=892 out=663 cacheRead=0        ← first call
[SUCCESS] [Michael Scott] state=NEEDS_INPUT in=892 out=692 cacheRead=1099     ← cache HIT
```

`cacheRead: 0` on the first call is expected — Anthropic hasn't seen the
prefix yet. On the second call within 5 minutes, it should be > 0.

### 2. In the Token Tracker UI

Navigate to `/token-tracker`. The Compression Skills panel shows:

```
┌─ Prompt Caching ──────────────────  [ACTIVE] ─┐
│  3,297    tokens read from cache               │
│  1,099    tokens written to cache              │
│                                                 │
│  Requires ≥1024 tokens in system prompt.       │
└─────────────────────────────────────────────────┘
```

- **READY** — infrastructure is wired but the cached prefix is too small
- **ACTIVE** — cache hits are happening
- **Tokens read** — cumulative cache reads since app start
- **Tokens written** — how many tokens Anthropic stored for reuse

### 3. In the Persistent Call Ledger

Same page. Each row shows the cost of a call. Notice a call with a large
`cacheRead` has lower cost per input token:

| Time     | In    | Out | Cost    |
| -------- | ----- | --- | ------- |
| 23:40:56 | 1,991 | 663 | $0.0167 |
| 23:41:14 | 1,991 | 692 | $0.0134 |

Same input size, second call cheaper because 1,099 of those 1,991 tokens
were served from cache.

## Database and config

### Single SQLite database

Everything lives in one file. No external database, no server.

**Location:** `./index.db` relative to the project root (or wherever the
app is launched from).

**Tables:**

| Table               | Purpose                                                |
| ------------------- | ------------------------------------------------------ |
| `classes`           | Indexed type declarations (class/interface/enum)       |
| `methods`           | Methods extracted from classes                         |
| `dependencies`      | Type-level dependencies (reserved for future use)      |
| `code_chunks`       | Retrievable chunks with embedding vectors (BLOB)       |
| `token_metrics`     | One row per LLM call (role, tokens, cost, cache stats) |
| `pipeline_sessions` | Multi-turn clarifier sessions                          |

**Inspecting:** IntelliJ's SQLite Inspector (bottom-left tab). Or install
the `sqlite3` CLI and query directly.

**Common queries:**

```sql
-- Chunk counts by type
SELECT chunk_type, COUNT(*) FROM code_chunks GROUP BY chunk_type;

-- Verify embedding size (1536 bytes = 384 floats)
SELECT COUNT(*) FROM code_chunks WHERE length(embedding) = 1536;

-- Session token usage
SELECT SUM(input_tokens_after + output_tokens_after)
FROM token_metrics WHERE session_id = '...';

-- Cache hit rate
SELECT SUM(cache_read_tokens) * 100.0 /
       SUM(cache_read_tokens + input_tokens_after)
FROM token_metrics;
```

### Config file

**Location:** project-local `config.yml` takes priority. Falls back to
`~/.relay-orchestrator/config.yml`.

**Override:** set `RELAY_DATA_DIR` env var to relocate.

**Schema:**

```yaml
provider: CLAUDE_API
githubToken: ""
anthropicApiKey: sk-ant-...
workspaceDir: /tmp/workspace
model: claude-sonnet-4-5
requestBudget: 5

repositories:
  - id: backend
    path: /path/to/your/repo
    sourceType: LOCAL_PATH
    status: INDEXED
    lastIndexed: "2026-09-22T23:18:09"

agents:
  enabled: true
  searchPaths:
    - "~/.relay-orchestrator/agents"
    - "classpath:agents"
  roles:
    orchestrator: michael
    analyst: oscar
    implementer: dwight
    tester: jim
    documenter: pam
  overrides:
    michael:
      temperature: 0.3
  fallbackPersona: generic
```

### Model files

**Location:** `./models/all-MiniLM-L6-v2/` (project-local).

**Files:** `model.onnx` (~90 MB), `tokenizer.json`, `vocab.txt`,
`config.json`.

**First run:** downloads automatically from HuggingFace.

**Offline use:** copy the whole project folder — model travels with
source. Or set `RELAY_MODELS_DIR` env var to point at a shared location.

## Run it

```bash
cd relay-orchestrator
mvn spring-boot:run
```

Open http://localhost:8081

**Windows memory note:** if `mvn` runs out of heap, set:

```powershell
$env:MAVEN_OPTS = "-Xmx1g -Xms256m -XX:MaxMetaspaceSize=256m"
mvn spring-boot:run
```

## Verification tests

### Test 1 — Embedding service

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/debug/embed?text=hello%20world"
```

Expect `dimension: 384` and `norm: ~1.0`. First call downloads the model
(30-90 seconds); subsequent calls return in ~20ms.

### Test 2 — Semantic similarity

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/debug/similarity?a=login+authentication&b=user+authentication+and+login"
# Expect cosine ~0.7+

Invoke-RestMethod -Uri "http://localhost:8081/debug/similarity?a=login+authentication&b=order+processing+payment"
# Expect cosine < 0.3
```

### Test 3 — Retrieval

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/debug/retrieve?story=add+a+method+to+PipelineOrchestrator"
```

Expect `flooredCount: 5` and top hits referencing `PipelineOrchestrator`.

### Test 4 — Full clarifier

Open `http://localhost:8081/`, type a concrete story, click
**START CLARIFICATION**. After the response arrives:

- Michael's questions should reference actual classes from your repo
- The log shows `[RETRIEVAL] Retrieved N chunks: ...`
- The session panel shows the token usage bar
- `/token-tracker` shows the call in the Persistent Call Ledger

### Test 5 — Prompt caching

Run the same story twice, 3 seconds apart. The second call's log line
should show `cacheRead > 0`. Check `/token-tracker` — the Compression
Skills panel should say `ACTIVE`.

## What is intentionally not built yet

| Feature                                  | Phase       | Note                                                     |
| ---------------------------------------- | ----------- | -------------------------------------------------------- |
| **Implementer agent** (writes code)      | 2           | The next milestone.                                      |
| **Tester agent** (runs tests)            | 3           | Depends on the implementer.                              |
| **Git integration** (branch, commit, PR) | 4           | Uses JGit + GitHub API.                                  |
| **PR monitoring** (CI status polling)    | 4           | 30-second poll loop.                                     |
| **Native packaging** (msi, dmg, deb)     | 5           | Uses `jpackage`.                                         |
| **Real Copilot client**                  | 5           | Currently a stub. Waits for a real Copilot subscription. |
| **Auto-update**                          | 6           | Optional polish.                                         |
| **Multi-user / login**                   | Not planned | Out of scope for a local-first single-user tool.         |

## Console and page inventory

| Route                 | Purpose                                             |
| --------------------- | --------------------------------------------------- |
| `/`                   | Pipeline Stages — submit a story, run the clarifier |
| `/settings`           | Connection form, provider switch, agent personas    |
| `/repositories`       | Add repos, index them, watch status badges          |
| `/workspace/topology` | Indexed classes and methods tree                    |
| `/token-tracker`      | Cost, tokens, per-role breakdown, cache stats       |
| `/logs`               | Full-page terminal log view                         |
| `/debug/embed`        | TEMP — embed a string                               |
| `/debug/similarity`   | TEMP — compare two strings                          |
| `/debug/retrieve`     | TEMP — inspect retrieval for a story                |

The `/debug/*` endpoints are for Phase 1.3 verification. They can be
deleted before packaging.

## Limitations

- Local single-user app. No OAuth, no multi-user, no cloud deployment.
- Anthropic is the only fully-wired provider. Copilot is a stub.
- Java is the only indexed language. Angular/TypeScript support is a
  Phase 6 addition — the abstraction is in place, the parser is not.
- Embeddings use MiniLM-L6-v2 (384-dim). Not a code-specific model, so
  retrieval scores top out around 0.30–0.60 for our metadata-style chunks.
- No auto-update. Users re-download new releases.
