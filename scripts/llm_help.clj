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

# 2. INSPECT a candidate before using it.
#    This returns a full \"function card\": args, purity, description,
#    docstring, real call-site examples, callers, callees.
code-search show com.acme.util.date/->display-str

# 3. ALWAYS verify by reading the actual source.
#    Use the file:line range from the card.
```

### How to read search results

Each result includes:
- `qualified_name` — `ns/name`. If the same name appears in clj and cljs
  with different bodies, you'll see two rows tagged by `lang`.
- `desc` — one-sentence description of what the function does.
- `gp` — general-purpose score (0..1). High = pure utility; low = domain-specific.
- `conf` — confidence (0..1) of the description.
- `tags` — semantic keywords.
- `rank` — BM25 score (more negative = better match).

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
