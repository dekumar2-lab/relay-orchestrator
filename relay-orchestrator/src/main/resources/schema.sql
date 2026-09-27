-- Relay Orchestrator — schema (single SQLite file)
--
-- Ownership boundaries are encoded in the table prefix:
--   idx_*   → Indexer service (code parsing, chunking, BM25)
--   orch_*  → Orchestrator service (sessions, artifacts)
--   tele_*  → Telemetry service (token metrics)
--
-- Every table that holds per-service data carries a `service_id` column.
-- For now, all rows use 'default'. When multi-service lands, filters
-- on service_id become the sharding key.

-- ============================================================
-- INDEXER SERVICE
-- ============================================================

CREATE TABLE IF NOT EXISTS idx_classes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  service_id TEXT NOT NULL DEFAULT 'default',
  repo_id TEXT NOT NULL,
  package_name TEXT,
  class_name TEXT,
  file_path TEXT,
  type_kind TEXT,
  annotations TEXT,
  extends_type TEXT,
  implements_types TEXT
);

CREATE TABLE IF NOT EXISTS idx_methods (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  service_id TEXT NOT NULL DEFAULT 'default',
  class_id INTEGER REFERENCES idx_classes(id) ON DELETE CASCADE,
  method_name TEXT,
  signature TEXT,
  return_type TEXT,
  parameters TEXT,
  line_start INTEGER,
  line_end INTEGER,
  calls_out TEXT
);

CREATE TABLE IF NOT EXISTS idx_dependencies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  service_id TEXT NOT NULL DEFAULT 'default',
  repo_id TEXT NOT NULL,
  source_class_id INTEGER REFERENCES idx_classes(id) ON DELETE CASCADE,
  target_type TEXT,
  dependency_kind TEXT,
  target_file_path TEXT
);

CREATE INDEX IF NOT EXISTS idx_classes_repo ON idx_classes(repo_id);
CREATE INDEX IF NOT EXISTS idx_classes_service ON idx_classes(service_id);
CREATE INDEX IF NOT EXISTS idx_methods_class ON idx_methods(class_id);
CREATE INDEX IF NOT EXISTS idx_deps_repo ON idx_dependencies(repo_id);