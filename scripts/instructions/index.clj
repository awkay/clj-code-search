(ns scripts.instructions.index
  "Two-pass indexer for the instruction-retrieval DB.

   Pass 1: per-doc LLM emits summary/explains/tags, sha-gated.
   Pass 2: per-doc LLM picks typed edges from FTS-narrowed candidates,
           gated by a composite sha of (doc-sha + candidate shas)."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [scripts.instructions.db :as idb]
   [scripts.instructions.llm :as llm]
   [scripts.lib.sha :as sha]))

(def default-db-path
  (or (System/getenv "INSTRUCTIONS_DB")
      ".code-intelligence/instructions.db"))

(def default-candidate-k 25)
(def default-confidence-floor 0.5)

;; -----------------------------------------------------------------------------
;; FS walk + content extraction
;; -----------------------------------------------------------------------------

(defn- md-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (str/lower-case (.getName f)) ".md")))

(defn- walk-md [^String dir]
  (let [root (io/file dir)]
    (when-not (.exists root)
      (throw (ex-info (str "Directory does not exist: " dir) {:dir dir})))
    (->> (file-seq root)
         (filter md-file?)
         (sort-by #(.getPath ^java.io.File %)))))

(defn- project-relative
  "Return `path` relative to `project-root`, normalized with forward slashes.
   Falls back to the absolute path if it doesn't sit under project-root."
  [project-root ^java.io.File f]
  (let [root (.toPath (.getCanonicalFile (io/file project-root)))
        p    (.toPath (.getCanonicalFile f))]
    (if (.startsWith p root)
      (str (.relativize root p))
      (str p))))

(defn- extract-title
  "First H1 (# ...) in body, else nil."
  [body]
  (some->> (str/split-lines body)
           (some (fn [l]
                   (when-let [m (re-matches #"\s*#\s+(.+?)\s*" l)]
                     (str/trim (nth m 1)))))))

(defn- read-doc
  "Read one file and produce the canonical doc map (no LLM fields yet)."
  [project-root ^java.io.File f]
  (let [body (slurp f)
        sha  (sha/sha256 body)]
    {:path     (project-relative project-root f)
     :sha      sha
     :title    (extract-title body)
     :body     body
     :abs-path (.getCanonicalPath f)}))

;; -----------------------------------------------------------------------------
;; Pass 1
;; -----------------------------------------------------------------------------

(defn- pass1-for-doc
  "Either reuse stored LLM fields (sha unchanged) or run pass-1. Returns a
   map ready for upsert-doc!."
  [db {:keys [path sha] :as doc} {:keys [model force?]}]
  (let [stored (idb/doc-row-by-path db path)]
    (if (and stored (= (:sha stored) sha) (not force?))
      ;; sha unchanged → reuse stored LLM fields
      (let [r (idb/get-doc db path)]
        (assoc doc
               :summary  (:summary r)
               :explains (idb/parse-explains r)
               :tags     (idb/parse-tags r)
               :model    (:analyzed_by_model r)
               :reused?  true))
      (let [r (llm/pass1 {:model model :doc doc})]
        (assoc doc
               :summary  (:summary r)
               :explains (:explains r)
               :tags     (:tags r)
               :model    model
               :reused?  false)))))

;; -----------------------------------------------------------------------------
;; Pass 2
;; -----------------------------------------------------------------------------

(defn- candidate-query-text
  "Text used to find candidate neighbors via FTS: explains + summary."
  [{:keys [summary explains]}]
  (str/join " " (cons (or summary "") (or explains []))))

(defn- candidates-composite-sha
  "Composite sha for cache-gating pass 2: sha(doc-sha + sorted candidate shas)."
  [doc-sha candidate-shas]
  (sha/short-sig
   (sha/sha256 (str doc-sha "|" (str/join "," (sort candidate-shas))))))

(defn- pass2-for-doc
  "Find candidates, gate on composite sha, run pass-2 LLM if needed, replace edges."
  [db {:keys [path sha] :as doc-with-summary}
   {:keys [model k confidence-floor force?]
    :or   {k                default-candidate-k
           confidence-floor default-confidence-floor}}]
  (let [qtext       (candidate-query-text doc-with-summary)
        cands-raw   (idb/fts-candidates db path qtext k)
        cands       (for [c cands-raw]
                      {:path     (:path c)
                       :title    (:title c)
                       :summary  (:summary c)
                       :explains (idb/parse-explains c)
                       :sha      (:sha c)})
        cand-paths  (mapv :path cands)
        comp-sha    (candidates-composite-sha sha (map :sha cands))
        stored-comp (some-> (idb/doc-row-by-path db path) :candidates_sha)
        skip?       (and (not force?) (= stored-comp comp-sha) stored-comp)]
    (if (or skip? (empty? cands))
      (do
        (when (empty? cands)
          (idb/replace-edges! db path []))
        (idb/set-candidates-sha! db path comp-sha)
        {:path path :skipped? true})
      (let [result (llm/pass2 {:model model :doc doc-with-summary :candidates cands})
            edges  (llm/edges-from-pass2 result cand-paths {:confidence-floor confidence-floor})]
        (idb/replace-edges! db path edges)
        (idb/set-candidates-sha! db path comp-sha)
        {:path path :edges (count edges) :skipped? false}))))

;; -----------------------------------------------------------------------------
;; Orchestration
;; -----------------------------------------------------------------------------

(defn- parallel-map
  "Bounded-parallel map: process `xs` in batches of `n` futures, preserving order."
  [n f xs]
  (->> xs
       (partition-all n)
       (mapcat (fn [batch] (->> batch (mapv #(future (f %))) (mapv deref))))
       vec))

(defn index!
  "Build/refresh the instruction index.

   opts:
     :db          DB path (default $INSTRUCTIONS_DB or .code-intelligence/instructions.db)
     :project-root  project root (default current dir)
     :model       Claude model alias (default haiku)
     :parallel    concurrent LLM calls (default 20)
     :k           candidate count per doc (default 25)
     :confidence-floor  min confidence for an edge to be stored (default 0.5)
     :force?      ignore caches"
  [dir {:keys [db project-root model parallel k confidence-floor force?]
        :or   {db               default-db-path
               project-root     (System/getProperty "user.dir")
               model            llm/default-model
               parallel         20
               k                default-candidate-k
               confidence-floor default-confidence-floor}}]
  (idb/open db)
  (let [files     (walk-md dir)
        _         (println (format "Found %d markdown files under %s" (count files) dir))
        docs      (mapv (partial read-doc project-root) files)
        root-rel  (project-relative project-root (io/file dir))
        ;; ---------- Pass 1 ----------
        _         (println "Pass 1: per-doc summary/explains/tags …")
        p1-results (parallel-map parallel
                                 (fn [d] (pass1-for-doc db d {:model model :force? force?}))
                                 docs)
        ;; Serial DB write (pod is single-threaded)
        _         (doseq [d p1-results]
                    (idb/upsert-doc! db (assoc d :root-dir root-rel)
                                     :force? force?))
        reused    (count (filter :reused? p1-results))
        analyzed  (- (count p1-results) reused)
        _         (println (format "Pass 1 done: %d analyzed, %d cached" analyzed reused))
        ;; ---------- Pass 2 ----------
        _         (println "Pass 2: typed-edge matching (FTS-narrowed) …")
        p2-results (parallel-map parallel
                                 (fn [d] (pass2-for-doc db d
                                                        {:model model :k k
                                                         :confidence-floor confidence-floor
                                                         :force? force?}))
                                 p1-results)
        skipped    (count (filter :skipped? p2-results))
        edged      (- (count p2-results) skipped)
        total-edges (->> p2-results (keep :edges) (reduce + 0))]
    (println (format "Pass 2 done: %d analyzed, %d cached, %d edges written"
                     edged skipped total-edges))
    {:files (count files)
     :pass1 {:analyzed analyzed :reused reused}
     :pass2 {:analyzed edged :skipped skipped :edges total-edges}}))

;; -----------------------------------------------------------------------------
;; CLI entry
;; -----------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [[a & rst] args opts {} positional []]
    (cond
      (nil? a) [opts positional]
      (= a "--db")       (recur (rest rst) (assoc opts :db (first rst)) positional)
      (= a "--model")    (recur (rest rst) (assoc opts :model (first rst)) positional)
      (= a "--parallel") (recur (rest rst) (assoc opts :parallel (Integer/parseInt (first rst))) positional)
      (= a "--k")        (recur (rest rst) (assoc opts :k (Integer/parseInt (first rst))) positional)
      (= a "--confidence-floor") (recur (rest rst) (assoc opts :confidence-floor (Double/parseDouble (first rst))) positional)
      (= a "--project-root") (recur (rest rst) (assoc opts :project-root (first rst)) positional)
      (= a "--force")    (recur rst (assoc opts :force? true) positional)
      :else              (recur rst opts (conj positional a)))))

(defn -main [& args]
  (let [[opts pos] (parse-args args)]
    (if (empty? pos)
      (do (println "usage: inst-search index [--db PATH] [--model NAME] [--parallel N]")
          (println "                        [--k N] [--confidence-floor F]")
          (println "                        [--project-root PATH] [--force] DIR")
          (System/exit 1))
      (let [{:keys [files pass1 pass2]} (index! (first pos) opts)]
        (println (format "Indexed %d files. Pass1: %s. Pass2: %s."
                         files pass1 pass2))))))
