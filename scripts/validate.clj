(ns scripts.validate
  "End-to-end indexer validation against simplymeet.

   Pipeline:
     1) Doctor check.
     2) clj-kondo over full src/main for cross-file caller info.
     3) Filter to the sample functions we care about for this pass.
     4) For each fn: deterministic enrichment is already in the record; call
        `claude -p` to produce the description; store rows.
     5) Print results table + latency + FTS sanity."
  (:require
   [clojure.string :as str]
   [scripts.doctor :as doctor]
   [scripts.lib.claude-cli :as claude-cli]
   [scripts.lib.db :as db]
   [scripts.lib.kondo :as kondo]))

(def db-path ".code-intelligence/code-index.db")

(def model
  (or (System/getenv "CODE_INDEX_CLAUDE_MODEL") "haiku"))

(def parallel 20)

(def simplymeet-root
  "/Users/tonykay/fulcrologic/simplymeet/src/main")

(def sample-files
  "Files whose functions we'll fully analyze. Everything ELSE in src/main is
   still linted (so we get caller info), but only these contribute rows."
  #{"/Users/tonykay/fulcrologic/simplymeet/src/main/simplymeet/lib/profile_storage.cljc"
    "/Users/tonykay/fulcrologic/simplymeet/src/main/simplymeet/web/profile_fetcher.cljc"
    "/Users/tonykay/fulcrologic/simplymeet/src/main/simplymeet/web/scroll_monitor.cljc"
    "/Users/tonykay/fulcrologic/simplymeet/src/main/simplymeet/application.cljc"})

(defn- run-once
  [fn-rec]
  (let [t0 (System/currentTimeMillis)]
    {:result (claude-cli/analyze-fn {:model model :fn-record fn-rec})
     :ms     (- (System/currentTimeMillis) t0)}))

(defn- store!
  [db fn-rec {:keys [description arg_descriptions return_description
                     tags domain_signals general_purpose_score confidence
                     analyzed-by-model]
              :or {analyzed-by-model model}}]
  (db/upsert-function!
   db (-> fn-rec
          (assoc :description-llm description
                 :arg-descriptions-llm arg_descriptions
                 :return-description-llm return_description
                 :tags-llm tags
                 :domain-signals-llm domain_signals
                 :general-purpose-score general_purpose_score
                 :confidence confidence
                 :analyzed-by-model analyzed-by-model
                 :callee-namespaces (->> (:callees fn-rec) (keep :to-ns) distinct vec))
          (select-keys
           [:ns :name :qualified-name :lang :sha
            :filename :line-start :line-end :col-start :col-end
            :arglists-edn :arities :defined-by :private? :docstring
            :pure-heuristic? :pure-heuristic-reasons :types-edn
            :caller-count :caller-namespaces :callee-namespaces :example-callers
            :description-llm :arg-descriptions-llm :return-description-llm
            :tags-llm :domain-signals-llm :general-purpose-score :confidence
            :analyzed-by-model]))))

(defn- parallel-map
  "Apply `f` over `coll` with at most `n` concurrent in-flight calls.
   Returns results in input order."
  [n f coll]
  (->> coll
       (partition-all n)
       (mapcat (fn [batch]
                 (->> batch
                      (mapv #(future (f %)))
                      (mapv deref))))))

(defn- already-indexed?
  "True iff a row exists with matching qualified-name+lang AND the same sha."
  [db {:keys [qualified-name lang sha]}]
  (let [{stored-sha :sha} (db/fn-row-by-qname db qualified-name lang)]
    (= stored-sha sha)))

(defn run-pipeline
  "Split into three phases so the sqlite pod doesn't get hit concurrently:
     1) serial cache-check: split fn-records into skipped (sha cached) and to-do
     2) parallel analyze: only the analyzer runs concurrently
     3) serial store!: write results to SQLite one at a time"
  [db fn-records]
  (let [{cached true to-do false}
        (group-by #(already-indexed? db %) fn-records)
        _ (binding [*out* *err*]
            (doseq [fn-rec cached]
              (println (format "  [SKIP] %-57s (sha cached)" (:qualified-name fn-rec)))))
        analyzed
        (parallel-map
         parallel
         (fn [fn-rec]
           (let [{:keys [result ms]} (run-once fn-rec)]
             (binding [*out* *err*]
               (println (format "  [LLM] %-58s %4d ms"
                                (:qualified-name fn-rec) ms)))
             {:fn-rec fn-rec :result result :ms ms}))
         to-do)]
    ;; Serial DB writes
    (doseq [{:keys [fn-rec result]} analyzed]
      (store! db fn-rec result))
    (concat
     (for [fn-rec cached]
       {:qname (:qualified-name fn-rec) :ms 0 :result nil :source :cached})
     (for [{:keys [fn-rec result ms]} analyzed]
       {:qname (:qualified-name fn-rec) :ms ms :result result :source :llm}))))

(defn- pct [xs p]
  (when (seq xs)
    (let [s   (vec (sort xs))
          idx (min (dec (count s)) (int (* p (count s))))]
      (nth s idx))))

(defn- print-results-table [rows]
  (println)
  (println "## Results (" model ")")
  (println)
  (println "| function | gp | conf | description |")
  (println "|---|---|---|---|")
  (doseq [{:keys [qname result]} rows]
    (printf "| %s | %.2f | %.2f | %s |\n"
            qname
            (double (:general_purpose_score result 0.0))
            (double (:confidence result 0.0))
            (-> (str (:description result))
                (str/replace #"\|" "\\\\|")
                (str/replace #"\n" " "))))
  (println))

(defn- print-stats [rows wall-ms]
  (let [llm (filter #(= :llm (:source %)) rows)
        llm-ms (map :ms llm)]
    (println "## Stats")
    (printf "  wall-clock:       %d ms\n" wall-ms)
    (printf "  LLM:              %d fns" (count llm))
    (when (seq llm-ms)
      (printf "  (p50=%d  p95=%d  max=%d ms per call)"
              (pct llm-ms 0.5) (pct llm-ms 0.95) (apply max llm-ms)))
    (println)
    (printf "  parallel:         %d concurrent claude calls\n" parallel))
  (println)
  (let [lows (filter #(< (:confidence (:result %) 0) 0.5) rows)]
    (println (count lows) "low-confidence rows (<0.5):")
    (doseq [{:keys [qname result]} lows]
      (println "  -" qname "conf=" (:confidence result)))))

(defn- print-fts-checks [db]
  (println)
  (println "## FTS5 sanity checks")
  (doseq [q ["serialize profile" "fetch profile from cache"
             "scroll observer" "override api url"]]
    (println " - query:" (pr-str q))
    (doseq [r (take 3 (db/fts-search db q 3))]
      (println "    " (:qualified_name r) "—" (:description_llm r)))))

(defn -main [& _args]
  (binding [*out* *err*]
    (println "validate: model =" model "parallel =" parallel)
    (when-not (doctor/ensure!) (System/exit 1)))
  (let [db          (db/open db-path)
        _           (binding [*out* *err*]
                      (println "validate: linting" simplymeet-root
                               "(for cross-file caller info)"))
        all-recs    (kondo/analyze simplymeet-root)
        target-recs (filter #(sample-files (:filename %)) all-recs)
        _           (binding [*out* *err*]
                      (println "  total fns linted:" (count all-recs))
                      (println "  target sample size:" (count target-recs) "fns"))
        t0          (System/currentTimeMillis)
        rows        (doall (run-pipeline db target-recs))
        wall-ms     (- (System/currentTimeMillis) t0)]
    (print-results-table rows)
    (print-stats rows wall-ms)
    (println)
    (println "DB rows in" db-path ":"
             (-> (db/query db "SELECT COUNT(*) AS n FROM functions") first :n))
    (print-fts-checks db)))
