-- Instruction retrieval index.
-- Project-local SQLite + FTS5 store for markdown "how-to" docs (skills,
-- references, project guides). Two LLM passes produce:
--   pass 1: per-doc {summary, explains[], tags[]} — isolated, sha-gated
--   pass 2: per-doc typed edges to other docs (prereq | companion | extends)
--           via FTS-narrowed candidate matching (no imagination)
--
-- Open with PRAGMA journal_mode=WAL.
--
-- Paths in `docs.path` are stored relative to the project root so the index
-- is portable across clones. The DB itself lives at
-- .code-intelligence/instructions.db and is intended to be checked in as a
-- warm cache.

CREATE TABLE IF NOT EXISTS docs (
  id                INTEGER PRIMARY KEY,
  path              TEXT NOT NULL UNIQUE,        -- project-relative
  sha               TEXT NOT NULL,               -- content sha of the .md file
  candidates_sha    TEXT,                        -- sha of sorted candidate shas at pass-2 time
  root_dir          TEXT,                        -- root passed to `index` (project-relative)
  title             TEXT,                        -- first H1 / frontmatter title / filename stem
  summary           TEXT,                        -- LLM one-sentence summary
  explains_json     TEXT,                        -- JSON array of capability phrases
  tags_json         TEXT,                        -- JSON array
  body              TEXT,                        -- raw markdown body (for FTS + show excerpt)
  analyzed_by_model TEXT,
  indexed_at        TEXT
);

CREATE INDEX IF NOT EXISTS idx_docs_path     ON docs(path);
CREATE INDEX IF NOT EXISTS idx_docs_sha      ON docs(sha);
CREATE INDEX IF NOT EXISTS idx_docs_root_dir ON docs(root_dir);

-- Typed knowledge-graph edges between docs.
-- kind ∈ {prereq, companion, extends}
CREATE TABLE IF NOT EXISTS edges (
  src_path    TEXT NOT NULL,
  dst_path    TEXT NOT NULL,
  kind        TEXT NOT NULL,
  confidence  REAL,
  PRIMARY KEY (src_path, dst_path, kind)
);

CREATE INDEX IF NOT EXISTS idx_edges_src  ON edges(src_path);
CREATE INDEX IF NOT EXISTS idx_edges_dst  ON edges(dst_path);
CREATE INDEX IF NOT EXISTS idx_edges_kind ON edges(kind);

-- FTS5 over the soft + body fields. Same tokenizer tweak as code_index so
-- punctuation-heavy doc names tokenize sensibly.
CREATE VIRTUAL TABLE IF NOT EXISTS docs_fts USING fts5(
  path,
  title,
  summary,
  explains,
  tags,
  body,
  content='docs',
  content_rowid='id',
  tokenize="unicode61 separators './-_!?:>' remove_diacritics 0"
);

CREATE TRIGGER IF NOT EXISTS docs_ai AFTER INSERT ON docs BEGIN
  INSERT INTO docs_fts(rowid, path, title, summary, explains, tags, body)
    VALUES (new.id, new.path, new.title, new.summary, new.explains_json, new.tags_json, new.body);
END;

CREATE TRIGGER IF NOT EXISTS docs_ad AFTER DELETE ON docs BEGIN
  INSERT INTO docs_fts(docs_fts, rowid, path, title, summary, explains, tags, body)
    VALUES ('delete', old.id, old.path, old.title, old.summary, old.explains_json, old.tags_json, old.body);
END;

CREATE TRIGGER IF NOT EXISTS docs_au AFTER UPDATE ON docs BEGIN
  INSERT INTO docs_fts(docs_fts, rowid, path, title, summary, explains, tags, body)
    VALUES ('delete', old.id, old.path, old.title, old.summary, old.explains_json, old.tags_json, old.body);
  INSERT INTO docs_fts(rowid, path, title, summary, explains, tags, body)
    VALUES (new.id, new.path, new.title, new.summary, new.explains_json, new.tags_json, new.body);
END;
