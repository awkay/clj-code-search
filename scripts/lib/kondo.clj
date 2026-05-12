(ns scripts.lib.kondo
  "Wraps the clj-kondo binary. Produces per-function records enriched with body
   text, deterministic analysis (arities, purity heuristic, types), and example
   caller snippets — everything except the LLM-only fields."
  (:require
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [scripts.lib.analysis :as ana]
   [scripts.lib.sha :as sha]))

(defn run-kondo [path]
  (let [{:keys [out]} (p/sh ["clj-kondo" "--lint" (str path)
                             "--config" "{:output {:analysis true :format :edn}}"]
                            {:out :string :err :string})]
    (edn/read-string out)))

(defn read-file-lines! [cache filename]
  (or (get @cache filename)
      (let [v (str/split-lines (slurp filename))]
        (swap! cache assoc filename v) v)))

(defn file-sha [filename]
  (sha/sha256 (slurp filename)))

(defn extract-body
  "Slice source text from `filename` using clj-kondo positions (1-based, end-col exclusive)."
  [cache {:keys [filename row col end-row end-col]}]
  (let [lines (read-file-lines! cache filename)
        r0    (dec row)
        rN    (dec end-row)]
    (if (= r0 rN)
      (subs (nth lines r0) (dec col) (dec end-col))
      (let [first-line   (subs (nth lines r0) (dec col))
            middle-lines (subvec lines (inc r0) rN)
            last-line    (subs (nth lines rN) 0 (dec end-col))]
        (str/join "\n" (concat [first-line] middle-lines [last-line]))))))

(defn- defn-like? [d]
  (#{'clojure.core/defn 'clojure.core/defn-
     'cljs.core/defn 'cljs.core/defn-
     'com.fulcrologic.guardrails.core/>defn
     'com.fulcrologic.guardrails.core/>defn-}
   (:defined-by->lint-as d (:defined-by d))))

(defn- call-site-snippet [cache {:keys [filename row]}]
  (let [lines (read-file-lines! cache filename)
        idx   (dec row)
        from  (max 0 (dec idx))
        to    (min (count lines) (+ idx 2))]
    (str/join "\n" (subvec lines from to))))

(defn- pick-caller-samples
  "Up to 5 callers, preferring distinct namespaces. Each sample has
   {:in (caller-ns/var or caller-ns/<top-level>) :snippet 3-line slice}.

   CLJC files yield duplicate var-usage records (one per :lang) at the same
   position, so we dedupe by [filename row col] before sampling."
  [cache callers]
  (let [unique       (->> callers
                          (group-by (juxt :filename :row :col))
                          vals
                          (map first))
        by-ns        (group-by :from unique)
        first-per-ns (map first (vals by-ns))
        rest-flat    (mapcat rest (vals by-ns))
        ordered      (concat first-per-ns rest-flat)]
    (->> ordered
         (take 5)
         (map (fn [c]
                {:in      (str (:from c) "/" (or (:from-var c) "<top-level>"))
                 :snippet (try (call-site-snippet cache c) (catch Exception _ nil))}))
         (filter :snippet)
         vec)))

(defn analyze
  "Run clj-kondo on `path` (file or dir) and return enriched function records.

   Options:
     :skip-filename? (fn [filename] -> boolean)
        When provided, var-defs in matching files are dropped before analysis.
        Used to honor the file-SHA cache: 'this file hasn't changed, don't
        re-analyze its functions.'"
  ([path] (analyze path nil))
  ([path {:keys [skip-filename?]}]
   (let [{:keys [analysis]} (run-kondo path)
         cache              (atom {})
         raw-defs           (cond->> (filter defn-like? (:var-definitions analysis))
                              skip-filename? (remove #(skip-filename? (:filename %))))
         var-defs           (->> raw-defs
                                 (group-by (juxt :ns :name :filename))
                                 (mapcat
                                  (fn [[_ ds]]
                                    (let [enriched (map #(assoc % ::body
                                                                (try (extract-body cache %)
                                                                     (catch Exception _ nil)))
                                                        ds)
                                          shas     (set (map #(sha/self-sig (::body %)) enriched))]
                                      (if (and (= 2 (count enriched)) (= 1 (count shas)))
                                        [(assoc (first enriched) :lang :cljc)]
                                        enriched)))))
         var-usages         (:var-usages analysis)
         usages-by-callee   (group-by (fn [u] (str (:to u) "/" (:name u))) var-usages)
         usages-by-from-var (->> var-usages
                                 (filter :from-var)
                                 (group-by (juxt :from :from-var)))
         file-shas          (atom {})
         ensure-file-sha!   (fn [f]
                              (or (get @file-shas f)
                                  (let [s (file-sha f)]
                                    (swap! file-shas assoc f s)
                                    s)))]
     (for [d var-defs]
       (let [qname       (str (:ns d) "/" (:name d))
             body        (or (::body d)
                             (try (extract-body cache d) (catch Exception _ nil)))
             callees     (->> (get usages-by-from-var [(:ns d) (:name d)] [])
                              (map #(hash-map :to-ns (:to %)
                                              :to-name (:name %)
                                              :arity (:arity %)))
                              distinct
                              vec)
             callers     (get usages-by-callee qname [])
             arglists    (ana/extract-arglists body)
             arities     (ana/arities-summary d)
             types       (ana/extract-guardrails-types body (:defined-by d))
             purity      (ana/pure-heuristic body)
             self-sig    (sha/self-sig body)
             callee-sigs (map (fn [{:keys [to-ns to-name arity]}]
                                (sha/short-sig
                                 (sha/sha256 (str to-ns "/" to-name "#" arity))))
                              callees)]
         {:ns                     (:ns d)
          :name                   (:name d)
          :qualified-name         qname
          :lang                   (name (or (:lang d) :clj))
          :defined-by             (:defined-by d)
          :private?               (boolean (:private d))
          :filename               (:filename d)
          :file-sha               (ensure-file-sha! (:filename d))
          :line-start             (:row d)
          :line-end               (:end-row d)
          :col-start              (:col d)
          :col-end                (:end-col d)
          :body                   body
          :docstring              (:doc d)
          :arglists-edn           arglists
          :arities                arities
          :types-edn              types
          :pure-heuristic?        (:pure? purity)
          :pure-heuristic-reasons (:reasons purity)
          :callees                callees
          :caller-count           (count callers)
          :caller-namespaces      (->> callers (keep :from) distinct vec)
          :example-callers        (pick-caller-samples cache callers)
          :sha                    (sha/composite-sig self-sig callee-sigs)})))))
