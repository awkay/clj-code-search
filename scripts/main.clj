(ns scripts.main
  "Single entry point for the `code-search` CLI distributed via bbin.

   Subcommands:
     index      Build/refresh the index for a source directory.
     search     Search the index (default when no subcommand given).
     show       Dump the full record for one function.
     doctor     Check environment (clj-kondo, claude, bb).
     llm-help   Print CLAUDE.md-ready instructions for AI agents.

   Examples:
     code-search index src/main
     code-search 'serialize a profile'        # search is the default
     code-search search 'jwt middleware'
     code-search show com.acme.util/format-date
     code-search llm-help >> CLAUDE.md"
  (:require
   [clojure.string :as str]))

(defn- print-usage []
  (println (str/trim "
code-search — Clojure code intelligence index

Usage:
  code-search [QUERY ...]              search (default)
  code-search index      SOURCE-DIR [opts]
  code-search index-deps [LIB-NAMES...]  pick deps.edn entries to index
  code-search search  QUERY...   [opts]
  code-search show    QUALIFIED-NAME [opts]
  code-search doctor
  code-search llm-help

Common options:
  --db PATH          SQLite path (default .code-intelligence/code-index.db
                     or $CODE_INDEX_DB)

Search options:
  --format plain|edn|json
  --limit N
  --mode or|and             default or
  --ns-filter CSV           restrict to namespaces (substring match)
  --ns-exclude CSV          exclude namespaces
  --ns-boost CSV            boost results in matching namespaces
  --boost-amount F          boost magnitude (default project config)
  --no-caller-boost         disable caller-graph boosting
  --no-config               ignore project .code-intelligence/config.edn
  -v, --verbose

Index options (index):
  --model NAME              claude model alias (default haiku)
  --parallel N              concurrent LLM calls (default 20)
  --force                   ignore caches

index-deps options:
  --all                     non-TTY: select every maven dep
  --fast                    no-LLM indexing of public docstrings (default)
  --no-fast                 full LLM analysis (slower, costs tokens)
  --deps-file PATH          alternate deps.edn
  --tmp-root DIR            temp extract root (default /tmp/claude-tools-deps)
  --model NAME              LLM model (default $CODE_INDEX_CLAUDE_MODEL or haiku)
  --parallel N              concurrent LLM file-batches (default 20)
  --chunk-size N            fns per LLM call (default 25)
  --debug                   dump raw LLM output head on parse failure

For full per-subcommand help:
  code-search index      --help
  code-search index-deps --help
  code-search search     --help
  code-search show       --help

`code-search llm-help` prints instructions intended for AI coding agents.")))

(defn -main [& args]
  (let [[sub & rst] args]
    (cond
      (or (nil? sub) (#{"-h" "--help" "help"} sub))
      (print-usage)

      (= sub "index")
      (do (require 'scripts.index) (apply (resolve 'scripts.index/-main) rst))

      (= sub "index-deps")
      (do (require 'scripts.index-deps) (apply (resolve 'scripts.index-deps/-main) rst))

      (= sub "search")
      (do (require 'scripts.search) (apply (resolve 'scripts.search/-search-main) rst))

      (= sub "show")
      (do (require 'scripts.search) (apply (resolve 'scripts.search/-show-main) rst))

      (= sub "doctor")
      (do (require 'scripts.doctor) ((resolve 'scripts.doctor/-main)))

      (= sub "llm-help")
      (do (require 'scripts.llm-help) ((resolve 'scripts.llm-help/-main)))

      ;; No subcommand match: treat all args as a search query.
      :else
      (do (require 'scripts.search) (apply (resolve 'scripts.search/-search-main) args)))))
