(ns scripts.search
  "CLI for retrieving function records from the code-index. Two operations:
     - search: BM25 FTS5 search over (qualified_name, description_llm, docstring,
       tags_llm, domain_signals_llm). Returns a ranked list.
     - show: dump the full record for a single qualified-name.

   Output formats: plain (default, line-oriented), edn, json."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [scripts.lib.db :as db]))

(def default-db-path
  (or (System/getenv "CODE_INDEX_DB")
      ".code-intelligence/code-index.db"))

(defn- parse-json [s] (when s (try (json/parse-string s true) (catch Exception _ nil))))

(defn- short-snippet [s n]
  (when s
    (let [s (str/trim s)]
      (if (> (count s) n) (str (subs s 0 n) "…") s))))

(defn- format-search-row [{:keys [qualified_name description_llm filename
                                  line_start line_end general_purpose_score
                                  confidence tags_llm rank]}]
  (str qualified_name "\n"
       "  file: " filename ":" line_start "-" line_end "\n"
       "  desc: " (or description_llm "(no description)") "\n"
       "  gp: " (format "%.2f" (double (or general_purpose_score 0.0)))
       "  conf: " (format "%.2f" (double (or confidence 0.0)))
       (when-let [tags (parse-json tags_llm)] (str "  tags: " (str/join ", " tags)))
       "  rank: " (format "%.4f" (double (or rank 0.0)))))

(defn- format-show-record [r]
  (let [arg-descs (parse-json (:arg_descriptions_llm r))
        callers   (parse-json (:example_callers r))
        callees   (parse-json (:callee_namespaces r))
        callerns  (parse-json (:caller_namespaces r))
        tags      (parse-json (:tags_llm r))
        signals   (parse-json (:domain_signals_llm r))
        reasons   (parse-json (:pure_heuristic_reasons r))]
    (str
     (:qualified_name r) "\n"
     "  file:    " (:filename r) ":" (:line_start r) "-" (:line_end r) "\n"
     "  lang:    " (:lang r) "\n"
     "  args:    " (or (:arglists_edn r) "?") "\n"
     "  pure:    " (if (= 1 (:pure_heuristic r)) "yes" "no")
     (when (seq reasons) (str " — " (str/join ", " reasons))) "\n"
     (when (:types_edn r) (str "  types:   " (:types_edn r) "\n"))
     "  by:      " (:analyzed_by_model r) "\n"
     "\n"
     "  Description:  " (or (:description_llm r) "(none)") "\n"
     (when (:return_description_llm r)
       (str "  Returns:      " (:return_description_llm r) "\n"))
     (when (seq arg-descs)
       (str "\n  Arguments:\n"
            (->> arg-descs
                 (map (fn [{:keys [name desc]}]
                        (str "    " name ": " desc)))
                 (str/join "\n"))
            "\n"))
     (when (:docstring r)
       (str "\n  Docstring: " (pr-str (:docstring r)) "\n"))
     (when (seq tags) (str "\n  Tags:    " (str/join ", " tags) "\n"))
     (when (seq signals) (str "  Signals: " (str/join ", " signals) "\n"))
     "\n  Calls (namespaces):  " (str/join ", " (or callees ["(none)"])) "\n"
     "  Callers:             " (:caller_count r) " sites across "
     (count (or callerns [])) " ns: "
     (str/join ", " (or callerns ["(none)"])) "\n"
     (when (seq callers)
       (str "\n  Example call-sites:\n"
            (->> callers
                 (map (fn [{:keys [in snippet]}]
                        (str "    in " in ":\n"
                             (->> (str/split-lines snippet)
                                  (map #(str "      " %))
                                  (str/join "\n")))))
                 (str/join "\n\n"))
            "\n")))))

(defn search!
  ([query] (search! query {}))
  ([query {:keys [db limit mode format]
           :or {db default-db-path limit 5 mode :or format :plain}}]
   (let [hits (db/fts-search db query {:limit limit :mode mode})]
     (case format
       :edn   (pp/pprint hits)
       :json  (println (json/generate-string hits {:pretty true}))
       (doseq [h hits]
         (println (format-search-row h))
         (println))))))

(defn show!
  ([qname] (show! qname {}))
  ([qname {:keys [db format] :or {db default-db-path format :plain}}]
   (let [r (first (db/query db "SELECT * FROM functions WHERE qualified_name = ? LIMIT 1" qname))]
     (cond
       (nil? r) (println "Not found:" qname)
       (= format :edn)  (pp/pprint r)
       (= format :json) (println (json/generate-string r {:pretty true}))
       :else (println (format-show-record r))))))

(defn- parse-args [args]
  (loop [[a & rst] args opts {} positional []]
    (cond
      (nil? a) [opts positional]
      (= a "--db")     (recur (rest rst) (assoc opts :db (first rst)) positional)
      (= a "--limit")  (recur (rest rst) (assoc opts :limit (Integer/parseInt (first rst))) positional)
      (= a "--mode")   (recur (rest rst) (assoc opts :mode (keyword (first rst))) positional)
      (= a "--format") (recur (rest rst) (assoc opts :format (keyword (first rst))) positional)
      :else            (recur rst opts (conj positional a)))))

(defn -search-main [& args]
  (let [[opts pos] (parse-args args)]
    (if (empty? pos)
      (do (println "usage: bb search [--db PATH] [--limit N] [--format plain|edn|json] QUERY...")
          (System/exit 1))
      (search! (str/join " " pos) opts))))

(defn -show-main [& args]
  (let [[opts pos] (parse-args args)]
    (if (empty? pos)
      (do (println "usage: bb show [--db PATH] [--format plain|edn|json] QUALIFIED-NAME")
          (System/exit 1))
      (show! (first pos) opts))))
