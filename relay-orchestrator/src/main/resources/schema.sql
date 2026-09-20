CREATE TABLE IF NOT EXISTS classes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repo_id TEXT NOT NULL,
  package_name TEXT,
  class_name TEXT,
  file_path TEXT,
  type_kind TEXT,
  annotations TEXT,
  extends_type TEXT,
  implements_types TEXT
);

CREATE TABLE IF NOT EXISTS methods (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  class_id INTEGER REFERENCES classes(id) ON DELETE CASCADE,
  method_name TEXT,
  signature TEXT,
  return_type TEXT,
  parameters TEXT,
  line_start INTEGER,
  line_end INTEGER,
  calls_out TEXT
);

CREATE TABLE IF NOT EXISTS dependencies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repo_id TEXT NOT NULL,
  source_class_id INTEGER REFERENCES classes(id) ON DELETE CASCADE,
  target_type TEXT,
  dependency_kind TEXT,
  target_file_path TEXT
);

CREATE INDEX IF NOT EXISTS idx_classes_repo ON classes(repo_id);
CREATE INDEX IF NOT EXISTS idx_methods_class ON methods(class_id);
CREATE INDEX IF NOT EXISTS idx_deps_repo ON dependencies(repo_id);
