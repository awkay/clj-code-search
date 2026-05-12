(ns scripts.instructions.llm
  "LLM passes for the instruction index.

   Pass 1 (per-doc, isolated): summary + explains[] + tags[].
   Pass 2 (per-doc, FTS-narrowed candidates): typed edges
   {:prereq [...], :companion [...], :extends [...]} with confidence.

   Both passes shell out to `claude -p --model haiku`."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def default-model "haiku")

(defn- truncate [s n]
  (if (and s (> (count s) n)) (str (subs s 0 n) "\n…[truncated]") s))

(defn- strip-fences [s]
  (let [s (str/trim s)
        s (str/replace s #"(?s)\A```(?:json)?\s*" "")
        s (str/replace s #"(?s)\s*```\s*\z" "")]
    (str/trim s)))

(defn- extract-json [s]
  (when s
    (or (try (json/parse-string s true) (catch Exception _ nil))
        (try (json/parse-string (strip-fences s) true) (catch Exception _ nil))
        (when-let [start (str/index-of s "{")]
          (try (json/parse-string (subs s start) true) (catch Exception _ nil))))))

(defn- run-claude [model prompt]
  (let [{:keys [out exit]} (p/sh ["claude" "-p"
                                  "--model" model
                                  "--no-session-persistence"
                                  "--output-format" "text"
                                  prompt]
                                 {:out :string :err :string :timeout 180000})]
    (when (zero? exit) out)))

;; -----------------------------------------------------------------------------
;; Pass 1: what does this doc EXPLAIN?
;; -----------------------------------------------------------------------------

(def ^:private pass1-system
  "You are a documentation analyst. Read the markdown doc and return ONE JSON object describing what the doc explains. JSON only — no prose, no markdown fences.")

(defn pass1-prompt
  "Build the pass-1 prompt. `doc` is {:path :title :body}."
  [{:keys [path title body]}]
  (str
   pass1-system "\n\n"
   "Doc path: " path "\n"
   (when title (str "Title: " title "\n"))
   "\n"
   "Content:\n"
   (truncate body 12000) "\n\n"
   "Return ONE JSON object with exactly these keys:\n"
   "{\n"
   "  \"summary\": <one sentence: what this doc teaches the reader to do>,\n"
   "  \"explains\": [<up to 5 short phrases naming concrete capabilities this doc teaches; each phrase ≤ 8 words; verb-first ('write a Fulcro mutation', 'configure FTS5 tokenizer')>],\n"
   "  \"tags\": [<2-6 lowercase keywords; single tokens preferred>]\n"
   "}\n"
   "Only describe what THIS doc itself explains. Do not list prerequisites,\n"
   "related topics, or things the reader is assumed to know.\n"
   "JSON ONLY."))

(defn pass1
  "Run pass 1. Returns parsed map, or a stub on failure."
  [{:keys [model doc] :or {model default-model}}]
  (let [prompt   (pass1-prompt doc)
        attempt #(some-> (run-claude model prompt) extract-json)]
    (or (attempt)
        (attempt)
        {:summary  "<analysis failed>"
         :explains []
         :tags     []
         :_failed  true})))

;; -----------------------------------------------------------------------------
;; Pass 2: which candidates are RELATED to this doc, and how?
;; -----------------------------------------------------------------------------

(def ^:private pass2-system
  "You are a documentation linker. Given a target doc and a shortlist of other docs (each with a summary and the capabilities it explains), pick which other docs are genuinely relevant context for the target. JSON only — no prose, no fences.")

(defn- format-candidate [{:keys [path title summary explains]}]
  (str "  - path: " path "\n"
       (when title    (str "    title: " title "\n"))
       (when summary  (str "    summary: " summary "\n"))
       (when (seq explains)
         (str "    explains:\n"
              (->> explains
                   (map #(str "      * " %))
                   (str/join "\n"))
              "\n"))))

(defn pass2-prompt
  "Build the pass-2 prompt. `doc` is {:path :title :body :summary :explains}.
   `candidates` is a seq of {:path :title :summary :explains}."
  [{:keys [doc candidates]}]
  (str
   pass2-system "\n\n"
   "TARGET DOC\n"
   "  path: " (:path doc) "\n"
   (when (:title doc)   (str "  title: " (:title doc) "\n"))
   (when (:summary doc) (str "  summary: " (:summary doc) "\n"))
   (when (seq (:explains doc))
     (str "  explains:\n"
          (->> (:explains doc)
               (map #(str "    * " %))
               (str/join "\n"))
          "\n"))
   "\n"
   "  body (excerpt):\n"
   (->> (str/split-lines (truncate (:body doc) 6000))
        (map #(str "    " %))
        (str/join "\n"))
   "\n\nCANDIDATES (other docs available in the index):\n"
   (->> candidates (map format-candidate) (str/join "\n"))
   "\n"
   "Pick which candidates are relevant context for TARGET. You may only\n"
   "reference candidate paths from the list above — do not invent paths.\n"
   "Use these edge kinds:\n"
   "  - prereq:    reader should read this BEFORE the target doc to follow it\n"
   "  - companion: closely related / alternative / commonly used together\n"
   "  - extends:   the target builds on or specializes this candidate\n"
   "\n"
   "Hard caps: ≤ 5 prereq, ≤ 8 companion, ≤ 5 extends. Omit categories with\n"
   "no good matches. A candidate may appear in at most one category. Skip\n"
   "anything below 0.5 confidence.\n"
   "\n"
   "Return ONE JSON object with exactly these keys:\n"
   "{\n"
   "  \"prereq\":    [{\"path\": <candidate-path>, \"confidence\": <0..1>}, ...],\n"
   "  \"companion\": [{\"path\": <candidate-path>, \"confidence\": <0..1>}, ...],\n"
   "  \"extends\":   [{\"path\": <candidate-path>, \"confidence\": <0..1>}, ...]\n"
   "}\n"
   "JSON ONLY."))

(defn pass2
  "Run pass 2. Returns parsed map of typed edges; stub on failure."
  [{:keys [model doc candidates] :or {model default-model}}]
  (let [prompt   (pass2-prompt {:doc doc :candidates candidates})
        attempt #(some-> (run-claude model prompt) extract-json)]
    (or (attempt)
        (attempt)
        {:prereq [] :companion [] :extends [] :_failed true})))

(defn edges-from-pass2
  "Flatten pass-2 result into seq of {:dst :kind :confidence} edges, filtered
   to candidate paths and confidence ≥ floor."
  [pass2-result candidate-paths {:keys [confidence-floor] :or {confidence-floor 0.5}}]
  (let [allowed (set candidate-paths)]
    (for [kind  [:prereq :companion :extends]
          entry (get pass2-result kind)
          :let  [dst (some-> (:path entry) str)
                 conf (some-> (:confidence entry) double)]
          :when (and dst
                     (allowed dst)
                     conf
                     (>= conf confidence-floor))]
      {:dst dst :kind (name kind) :confidence conf})))
