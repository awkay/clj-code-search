(ns scripts.lib.claude-cli
  "Analyze a function by shelling `claude -p` under the user's Max subscription.
   Trades 'fully local' for higher quality + real parallelism (no GPU contention)."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def default-model "haiku")

(def ^:private system-prompt
  "You are a Clojure code analyst. Given a function plus deterministic facts and example call-sites, return ONE JSON object with the requested fields. No prose, no markdown fences — JSON only.")

(defn- truncate [s n]
  (if (and s (> (count s) n)) (str (subs s 0 n) "\n;; [truncated]") s))

(defn- format-callers [example-callers]
  (when (seq example-callers)
    (->> example-callers
         (map (fn [{:keys [in snippet]}]
                (str "  in " in ":\n"
                     (->> (str/split-lines (truncate snippet 400))
                          (map #(str "    " %)) (str/join "\n")))))
         (str/join "\n\n"))))

(defn- build-prompt
  [{:keys [ns name arglists-edn arities private? body
           pure-heuristic? pure-heuristic-reasons types-edn example-callers]}]
  (str
   system-prompt "\n\n"
   "Namespace: " ns "\n"
   "Function: " name (when private? " (private)") "\n"
   "Arglists: " (or arglists-edn (str "(" arities ")")) "\n"
   (when types-edn (str "Guardrails spec: " types-edn "\n"))
   "Purity (heuristic): " (if pure-heuristic? "appears pure" "side-effecting")
   (when (seq pure-heuristic-reasons)
     (str " — signals: " (str/join ", " pure-heuristic-reasons)))
   "\n"
   "\nSource:\n" (truncate body 6000) "\n"
   (when-let [callers (format-callers example-callers)]
     (str "\nExample call sites (real code):\n" callers "\n"))
   "\nReturn ONE JSON object with exactly these keys:\n"
   "{\n"
   "  \"description\": <one sentence: what it does and what it returns>,\n"
   "  \"arg_descriptions\": [{\"name\": <arg-name>, \"desc\": <brief>}, ...],\n"
   "  \"return_description\": <kind of value + meaning>,\n"
   "  \"tags\": [<2-6 lowercase keywords>],\n"
   "  \"domain_signals\": [<project-specific terms>],\n"
   "  \"general_purpose_score\": <0..1>,\n"
   "  \"confidence\": <0..1>\n"
   "}\n"
   "JSON ONLY. No markdown fences. No commentary."))

(defn- strip-fences
  "Claude often wraps JSON in ```json fences despite instructions — strip them."
  [s]
  (let [s (str/trim s)
        s (str/replace s #"(?s)\A```(?:json)?\s*" "")
        s (str/replace s #"(?s)\s*```\s*\z" "")]
    (str/trim s)))

(defn- extract-json
  "Best-effort extraction: try whole, then strip fences, then locate first { and
   try parse from there. Returns nil if all fail."
  [s]
  (or (try (json/parse-string s true) (catch Exception _ nil))
      (try (json/parse-string (strip-fences s) true) (catch Exception _ nil))
      (when-let [start (str/index-of s "{")]
        (try (json/parse-string (subs s start) true) (catch Exception _ nil)))))

(defn- run-claude
  [model prompt]
  (let [{:keys [out exit]} (p/sh ["claude" "-p"
                                  "--model" model
                                  "--no-session-persistence"
                                  "--output-format" "text"
                                  prompt]
                                 {:out :string :err :string :timeout 120000})]
    (when (zero? exit) out)))

(defn analyze-fn
  "Run one analysis call. Returns the parsed JSON map; never throws.
   One retry on bad JSON; final fallback returns a confidence=0 stub."
  [{:keys [model fn-record] :or {model default-model}}]
  (let [prompt (build-prompt fn-record)
        attempt (fn []
                  (some-> (run-claude model prompt) extract-json))]
    (or (attempt)
        (attempt)
        {:description           "<analysis failed>"
         :arg_descriptions      []
         :return_description    ""
         :tags                  []
         :domain_signals        []
         :general_purpose_score 0.0
         :confidence            0.0})))
