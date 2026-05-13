-- Code intelligence index.
-- Open with PRAGMA journal_mode=WAL for concurrent reader/writer support.
--
-- Two tables:
--   files     — file-level SHA cache. If file_sha matches, the whole file is skipped.
--   functions — one row per defn, keyed by qualified_name. Most fields are
--               deterministic (from clj-kondo / source). Only the *_llm fields
--               are produced by Ollama.

CREATE TABLE IF NOT EXISTS files (
  filename     TEXT PRIMARY KEY,
  file_sha     TEXT NOT NULL,
  analyzed_at  TEXT
);

CREATE TABLE IF NOT EXISTS functions (
  id                       INTEGER PRIMARY KEY,

  -- Identity (deterministic). CLJC files emit one row per language; uniqueness
  -- is over (qualified_name, lang).
  ns                       TEXT NOT NULL,
  name                     TEXT NOT NULL,
  qualified_name           TEXT NOT NULL,
  lang                     TEXT NOT NULL,           -- 'clj' | 'cljs'
  sha                      TEXT NOT NULL,           -- self+callees composite fingerprint

  -- Source location (deterministic; updated on file-SHA change even if fn-SHA stable)
  filename                 TEXT,
  line_start               INTEGER,
  line_end                 INTEGER,
  col_start                INTEGER,
  col_end                  INTEGER,

  -- Shape (deterministic)
  arglists_edn             TEXT,                    -- e.g. "([x] [x y])"
  arities_json             TEXT,                    -- e.g. {"fixed":[1,2],"varargs":null}
  defined_by               TEXT,                    -- clojure.core/defn, .../>defn, ...
  private                  INTEGER,                 -- 0/1
  docstring                TEXT,                    -- raw, as found

  -- Purity heuristic (deterministic from body scan)
  pure_heuristic           INTEGER,                 -- 0/1
  pure_heuristic_reasons   TEXT,                    -- JSON array of strings

  -- Types (deterministic for >defn; null for plain defn)
  types_edn                TEXT,                    -- e.g. "[int? int? => int?]"

  -- Callers/callees (deterministic)
  caller_count             INTEGER,
  caller_namespaces        TEXT,                    -- JSON array
  callee_namespaces        TEXT,                    -- JSON array
  example_callers          TEXT,                    -- JSON array of {in, snippet}

  -- LLM-produced fields (the only soft-judgment fields)
  description_llm          TEXT,                    -- one-sentence what-it-does
  arg_descriptions_llm     TEXT,                    -- JSON array of {name, desc, source}
  return_description_llm   TEXT,
  tags_llm                 TEXT,                    -- JSON array
  domain_signals_llm       TEXT,                    -- JSON array
  general_purpose_score    REAL,                    -- 0-1
  confidence               REAL,                    -- 0-1
  analyzed_by_model        TEXT,

  indexed_at               TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_functions_qname_lang ON functions(qualified_name, lang);
CREATE INDEX IF NOT EXISTS idx_functions_sha           ON functions(sha);
CREATE INDEX IF NOT EXISTS idx_functions_gp_score      ON functions(general_purpose_score);
CREATE INDEX IF NOT EXISTS idx_functions_ns            ON functions(ns);
CREATE INDEX IF NOT EXISTS idx_functions_confidence    ON functions(confidence);
CREATE INDEX IF NOT EXISTS idx_functions_filename      ON functions(filename);

-- Custom tokenizer: split on Clojure-ish punctuation so qualified names like
-- `simplymeet.web.scroll-monitor/create-observer!` tokenize as
-- [simplymeet, web, scroll, monitor, create, observer]. Diacritics off (irrelevant
-- here). Case folding is on by default.
CREATE VIRTUAL TABLE IF NOT EXISTS functions_fts USING fts5(
  qualified_name,
  description_llm,
  docstring,
  tags_llm,
  domain_signals_llm,
  content='functions',
  content_rowid='id',
  tokenize="unicode61 separators './-_!?:>' remove_diacritics 0"
);

CREATE TRIGGER IF NOT EXISTS functions_ai AFTER INSERT ON functions BEGIN
  INSERT INTO functions_fts(rowid, qualified_name, description_llm, docstring, tags_llm, domain_signals_llm)
    VALUES (new.id, new.qualified_name, new.description_llm, new.docstring, new.tags_llm, new.domain_signals_llm);
END;

CREATE TRIGGER IF NOT EXISTS functions_ad AFTER DELETE ON functions BEGIN
  INSERT INTO functions_fts(functions_fts, rowid, qualified_name, description_llm, docstring, tags_llm, domain_signals_llm)
    VALUES ('delete', old.id, old.qualified_name, old.description_llm, old.docstring, old.tags_llm, old.domain_signals_llm);
END;

CREATE TRIGGER IF NOT EXISTS functions_au AFTER UPDATE ON functions BEGIN
  INSERT INTO functions_fts(functions_fts, rowid, qualified_name, description_llm, docstring, tags_llm, domain_signals_llm)
    VALUES ('delete', old.id, old.qualified_name, old.description_llm, old.docstring, old.tags_llm, old.domain_signals_llm);
  INSERT INTO functions_fts(rowid, qualified_name, description_llm, docstring, tags_llm, domain_signals_llm)
    VALUES (new.id, new.qualified_name, new.description_llm, new.docstring, new.tags_llm, new.domain_signals_llm);
END;

-- Optional embeddings layer. Populated by `code-search embed-index`. Stores one
-- normalized float32 vector per function for the current model. content_sha is
-- the SHA of the text that was embedded — used to skip re-embedding when the
-- LLM-described summary hasn't changed. If the model name changes, all rows
-- for the old model should be deleted (do a rebuild).
CREATE TABLE IF NOT EXISTS embeddings (
  function_id    INTEGER PRIMARY KEY REFERENCES functions(id) ON DELETE CASCADE,
  model          TEXT    NOT NULL,
  dim            INTEGER NOT NULL,
  content_sha    TEXT    NOT NULL,
  vec            TEXT    NOT NULL,       -- base64 of normalized float32 LE, length = dim*4 bytes
  embedded_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_embeddings_model ON embeddings(model);

-- Clean up embeddings when their function disappears.
CREATE TRIGGER IF NOT EXISTS functions_ad_embeddings AFTER DELETE ON functions BEGIN
  DELETE FROM embeddings WHERE function_id = old.id;
END;
