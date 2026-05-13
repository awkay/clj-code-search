(ns scripts.llm-help
  "Prints CLAUDE.md-ready instructions teaching an AI coding agent when and how
   to use the `code-search` tool. Pipe to your project's CLAUDE.md (or whatever
   your agent reads): `code-search llm-help >> CLAUDE.md`")

(def help-text
  "## Code intelligence (code-search)

This project has a per-function semantic index of its Clojure / ClojureScript
source. Use it BEFORE writing a new utility function — there is often an
existing one that does what you need.

### When to use

Use `code-search` proactively when you are about to:
- Write a new utility (date formatting, validation, coercion, http, etc.) —
  someone may already have written it.
- Refactor or call a function and need to know what its arguments mean.
- Investigate \"how does this codebase do X?\" before designing a new approach.

Do NOT use `code-search` for:
- Looking up the exact location of a symbol you already know — use `grep`/Read.
- Browsing recent changes — use `git log`.

### Commands

```bash
# 1. SEARCH by intent (default subcommand — works for natural language)
code-search 'validate an email address'
code-search 'format a date for display'
code-search 'send a push notification'

# Useful flags (full list: `code-search --help`):
#   --ns-filter LIST    hard-include: only nses containing any csv substring
#   --ns-exclude LIST   hard-exclude: drop nses containing any csv substring
#   --ns-boost LIST     soft boost (default: \"lib,core\" when no filter given)
#   --no-caller-boost   disable the caller_count ranking term
#   --limit N           more/fewer results (default 5)
#   -v, --verbose       expanded per-result output (file, gp, conf, tags, score)

# 2. INSPECT a candidate before using it.
#    This returns a full \"function card\": args, purity, description,
#    docstring, real call-site examples, callers, callees.
code-search show com.acme.util.date/->display-str

# 3. ALWAYS verify by reading the actual source.
#    Use the file:line range from the card.
```

### How to read search results

Default output is two lines per hit:

```
ns/name
  one-sentence description
```

Pass `-v` for the verbose form, which adds:
- `file:line_start-line_end` — exact source location.
- `gp` — general-purpose score (0..1). High = pure utility; low = domain-specific.
- `conf` — confidence (0..1) of the description.
- `callers` — number of call-sites in the indexed tree.
- `tags` — semantic keywords.
- `score` — final ranking score (more negative = better match). Composed of
  BM25 + ns boost (-2.0 on match) − 3·ln(1 + caller_count). Library-ish
  namespaces and well-used functions are deliberately preferred.

CLJC files emit one row per language; if the same `ns/name` appears for both
clj and cljs with different bodies you'll see two hits.

### Project defaults: .code-search.edn

If the repo has a `.code-search.edn` (searched upward from cwd), its keys act
as defaults under any CLI flags. Useful for tuning a project's preferences
without retyping flags. Example:

```clojure
{:limit      20
 :ns-boost   [\"lib\" \"core\" \"util\"]
 :ns-exclude [\"test\" \"spec\"]}
```

Pass `--no-config` to ignore it for one invocation.

### Decision rule

When deciding whether to reuse an existing function:

1. If a result has `gp >= 0.5` and `conf >= 0.7`, treat it as a candidate.
2. Run `code-search show <qualified-name>` to read the full card.
3. ALWAYS read the actual source via the file:line range before calling it.
   Descriptions can be subtly wrong; the source is ground truth.
4. If nothing in the top 5 results matches your need, then it's safe to write
   a new function — but consider where it belongs (use the existing
   namespace's domain vocabulary).

### Index hygiene

The index lives in `.code-intelligence/code-index.db` (gitignored sidecars).
After significant changes to a source tree, refresh:

```bash
code-search index src/main
```

The indexer is incremental — unchanged files are skipped via SHA cache, so
re-running on a clean tree is near-instant.

### When `code-search` fails

If a query returns no useful results, fall back to:
1. `grep -r 'keyword' src/` for name-level search.
2. Asking the user what they call the concept in this codebase.

Do not invent function names from descriptions — always verify the
qualified-name exists before calling it.
")

(defn -main [& _]
  (println help-text))
