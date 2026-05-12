(ns scripts.lib.analysis
  "Deterministic enrichment derived from source text + clj-kondo records.
   The LLM never sees the *output* of this namespace as 'judgment' — these are
   facts about the code, not opinions."
  (:require
   [clojure.string :as str]
   [rewrite-clj.node :as n]
   [rewrite-clj.zip :as z]))

;; -----------------------------------------------------------------------------
;; Purity heuristic
;; -----------------------------------------------------------------------------

(def impurity-signals
  "Patterns whose presence in a function body strongly suggests side effects.
   `:re` is the regex to match; `:reason` is what we record if it hits."
  ;; Note: `\b` next to `!` doesn't behave as expected because `!` is non-word,
  ;; so we anchor bang-words with `(?=\s|\)|$)` instead.
  [{:re #"\b(swap!|reset!|alter|ref-set|send|send-off)(?=\s|\)|$)" :reason "mutates a ref/atom/agent"}
   {:re #"\batom\b"                                                :reason "creates an atom"}
   {:re #"\bvolatile!(?=\s|\)|$)"                                  :reason "volatile state"}
   {:re #"\bset!(?=\s|\)|$)"                                       :reason "uses set! (host mutation)"}
   {:re #"\bwith-open\b"                                           :reason "manages a closeable resource"}
   ;; *-str variants are pure — match only the bare print fn followed by space/paren.
   {:re #"\(\s*(println|print|prn|pr)(?=\s|\))"                    :reason "I/O: println/print"}
   {:re #"\bspit\b|\bslurp\b"                                 :reason "I/O: file"}
   {:re #"\bclojure\.java\.io\b|\bio/(reader|writer|file)\b"  :reason "I/O: clojure.java.io"}
   {:re #"\bjs/"                                              :reason "JS interop"}
   {:re #"\bjava\.|\(\.[a-zA-Z]"                              :reason "Java interop"}
   {:re #"\bd/transact\b|\bdatomic\b|\bxt/submit-tx\b"        :reason "database write"}
   {:re #"\bhttp/(post|put|delete|patch)\b"                   :reason "HTTP write"}])

(defn pure-heuristic
  "Returns {:pure? boolean :reasons [strs]}. False means we found evidence of
   side effects; true means we didn't (NOT a proof of purity)."
  [body-source]
  (let [reasons (->> impurity-signals
                     (keep (fn [{:keys [re reason]}]
                             (when (and body-source (re-find re body-source)) reason)))
                     distinct
                     vec)]
    {:pure?   (empty? reasons)
     :reasons reasons}))

;; -----------------------------------------------------------------------------
;; Arglist extraction
;;
;; clj-kondo gives us :fixed-arities (set) and :varargs-min-arity, but not the
;; arglist vectors themselves. We slice them out of the source body by finding
;; the [...] form(s) after the function name. Handles multi-arity defs by
;; collecting each top-level `([...] body...)` clause.
;; -----------------------------------------------------------------------------

(defn- vector-children
  "Walk forward from a zloc, skipping whitespace/comments/uneval/metadata, and
   collect every top-level :vector child (as raw strings) until we run out.
   Used to find arglist vectors at any nesting level."
  [zloc]
  (loop [z zloc acc []]
    (cond
      (nil? z) acc
      (= :vector (z/tag z)) (recur (z/right z) (conj acc (z/string z)))
      :else (recur (z/right z) acc))))

(defn- arglists-from-list
  "Given a zloc at a list whose first child is a [arglist], return the [arglist]
   string. Otherwise nil."
  [list-zloc]
  (when (and list-zloc (= :list (z/tag list-zloc)))
    (let [first-child (-> list-zloc z/down)]
      (when (and first-child (= :vector (z/tag first-child)))
        (z/string first-child)))))

(defn extract-arglists
  "Given the full body source of a def* form, return the arglist vector(s) as
   text, e.g. \"([x])\" or \"([x] [x y])\". Uses rewrite-clj for reader-aware
   parsing (handles reader conditionals, metadata, namespaced maps, etc.)."
  [body]
  (when body
    (try
      ;; Walk into the outer (defn ...) form, then past the macro name (defn/>defn/...)
      ;; and the function name to find the first significant form.
      (let [root  (z/of-string body {:track-position? false})
            ;; root is on the outer list; descend to its first child
            macro (z/down root)
            ;; advance past macro symbol → at fn-name (or metadata-decorated name)
            after-macro (z/right macro)
            ;; advance past name; metadata before the name is part of the same node so this is one hop.
            after-name  (z/right after-macro)
            ;; Walk right skipping docstring (token :token string), attr-map (:map),
            ;; and any further metadata, until we find a :vector (single-arity)
            ;; or :list (first multi-arity clause).
            anchor
            (loop [z after-name]
              (cond
                (nil? z) nil
                (#{:vector :list} (z/tag z)) z
                :else (recur (z/right z))))]
        (cond
          (nil? anchor) nil

          (= :vector (z/tag anchor))
          (str "(" (z/string anchor) ")")

          (= :list (z/tag anchor))
          ;; Collect every sibling :list whose first child is a :vector.
          (let [arglists (loop [z anchor acc []]
                           (cond
                             (nil? z) acc
                             (= :list (z/tag z))
                             (if-let [a (arglists-from-list z)]
                               (recur (z/right z) (conj acc a))
                               (recur (z/right z) acc))
                             :else (recur (z/right z) acc)))]
            (when (seq arglists)
              (str "(" (str/join " " arglists) ")")))))
      (catch Exception _ nil))))

;; -----------------------------------------------------------------------------
;; Guardrails spec extraction
;;
;; A guardrails fn looks like:
;;   (>defn name "doc" [args] [spec1 spec2 => ret-spec] body)
;; or with multi-arity:
;;   (>defn name "doc" ([args] [spec => ret] body) ([a b] [s s => r] body))
;;
;; We just locate the FIRST `[ ... => ... ]` vector and return its raw text.
;; -----------------------------------------------------------------------------

(defn extract-guardrails-types [body defined-by]
  (when (and body
             (#{'com.fulcrologic.guardrails.core/>defn
                'com.fulcrologic.guardrails.core/>defn-} defined-by))
    (when-let [m (re-find #"\[[^\[\]]*=>\s*[^\[\]]*\]" body)]
      m)))

;; -----------------------------------------------------------------------------
;; Docstring per-arg parsing (best-effort)
;;
;; Recognizes patterns like:
;;   x - the input value
;;   x — the input value
;;   x: the input value
;;   - x: the input value
;; one per line. Returns a vector of {:name :desc :source "docstring"} or [].
;; -----------------------------------------------------------------------------

(defn parse-docstring-args [doc arg-names]
  (when (and doc (seq arg-names))
    (let [name-set (set (map name arg-names))]
      (->> (str/split-lines doc)
           (keep (fn [line]
                   (when-let [[_ nm desc]
                              (re-find #"^[\s\-\*]*([A-Za-z_][\w?!*\-]*)\s*[\-—:]\s*(.+)$"
                                       (str/trim line))]
                     (when (name-set nm)
                       {:name nm :desc (str/trim desc) :source "docstring"}))))
           distinct
           vec))))

;; -----------------------------------------------------------------------------
;; Arities summary
;; -----------------------------------------------------------------------------

(defn arities-summary [{:keys [fixed-arities varargs-min-arity]}]
  {:fixed   (vec (sort (or fixed-arities #{})))
   :varargs varargs-min-arity})

