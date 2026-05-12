(ns scripts.lib.analysis-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scripts.lib.analysis :as ana]))

;; -----------------------------------------------------------------------------
;; pure-heuristic
;; -----------------------------------------------------------------------------

(deftest pure-heuristic-test
  (testing "obviously-pure functions"
    (doseq [body ["(defn add [x y] (+ x y))"
                  "(defn doubled [x] (* x 2))"
                  "(defn s [x] (pr-str x))"          ; pr-str is pure (returns string)
                  "(defn fmt [s] (str/upper-case s))"]]
      (is (:pure? (ana/pure-heuristic body)) (str "should be pure: " body))))

  (testing "side-effecting markers"
    (doseq [[label body reason-substr] [["swap!"   "(defn f [a x] (swap! a assoc :v x))" "mutates"]
                                        ["reset!"  "(defn f [a] (reset! a 1))"           "mutates"]
                                        ["set!"    "(defn f [] (set! *warn* true))"      "host mutation"]
                                        ["prn"     "(defn f [x] (prn x))"                "println/print"]
                                        ["spit"    "(defn f [p s] (spit p s))"           "I/O: file"]
                                        ["slurp"   "(defn f [p] (slurp p))"              "I/O: file"]
                                        ["atom"    "(defn f [] (atom {}))"               "atom"]
                                        ["js/"     "(defn f [] (.-location js/window))"  "JS interop"]
                                        ["with-open" "(defn f [] (with-open [r (foo)] (bar r)))" "closeable"]]]
      (let [{:keys [pure? reasons]} (ana/pure-heuristic body)]
        (is (false? pure?) (str "should be impure: " label))
        (is (some #(str/includes? % reason-substr) reasons)
            (str "expected reason like " reason-substr " in " reasons)))))

  (testing "false-positive guards (must NOT be flagged impure)"
    (doseq [[label body] [["bang-suffix in defn name"  "(defn save! [x] (+ x 1))"]
                          ["pr-str (string-returning)" "(defn f [x] (pr-str x))"]
                          ["prn-str (string-returning)" "(defn f [x] (prn-str x))"]
                          ["function called atomic"    "(defn atomic [x] (* x x))"]]]
      (is (:pure? (ana/pure-heuristic body))
          (str "should NOT be flagged impure: " label))))

  (testing "nil/empty body"
    (is (:pure? (ana/pure-heuristic nil)))
    (is (:pure? (ana/pure-heuristic "")))))

;; -----------------------------------------------------------------------------
;; extract-arglists
;; -----------------------------------------------------------------------------

(deftest extract-arglists-test
  (testing "single-arity"
    (is (= "([profile])" (ana/extract-arglists "(defn ser [profile] (pr-str profile))"))))

  (testing "multi-arity"
    (is (= "([] [x] [x y])"
           (ana/extract-arglists "(defn m ([] :zero) ([x] x) ([x y] (+ x y)))"))))

  (testing "with metadata"
    (is (= "([x])"
           (ana/extract-arglists "(defn ^:private foo \"d\" [x] x)"))))

  (testing "with attr-map"
    (is (= "([x y])"
           (ana/extract-arglists "(defn foo \"d\" {:added \"1.0\"} [x y] (+ x y))"))))

  (testing "with docstring"
    (is (= "([profile-data])"
           (ana/extract-arglists "(defn ser \"the docs\" [profile-data] (pr-str profile-data))"))))

  (testing "reader-conditional in body doesn't confuse parser"
    (is (= "([x])"
           (ana/extract-arglists
            "(defn foo [x] #?(:clj (Integer/parseInt x) :cljs (js/parseInt x)))"))))

  (testing "namespaced-map literal in body"
    (is (= "([m])"
           (ana/extract-arglists "(defn foo [m] #:my{:k 1})"))))

  (testing "guardrails >defn"
    (is (= "([x y])"
           (ana/extract-arglists "(>defn calc \"d\" [x y] [int? int? => int?] (+ x y))"))))

  (testing "malformed input returns nil safely (no throw)"
    (is (nil? (ana/extract-arglists "(defn busted"))))

  (testing "nil input"
    (is (nil? (ana/extract-arglists nil)))))

;; -----------------------------------------------------------------------------
;; extract-guardrails-types
;; -----------------------------------------------------------------------------

(deftest extract-guardrails-types-test
  (testing "extracts the [spec ... => ret] form for >defn"
    (is (= "[int? int? => int?]"
           (ana/extract-guardrails-types
            "(>defn calc [x y] [int? int? => int?] (+ x y))"
            'com.fulcrologic.guardrails.core/>defn))))

  (testing "extracts for >defn-"
    (is (= "[map? => string?]"
           (ana/extract-guardrails-types
            "(>defn- mapper [m] [map? => string?] (str m))"
            'com.fulcrologic.guardrails.core/>defn-))))

  (testing "returns nil for plain defn even if body looks like a spec"
    (is (nil? (ana/extract-guardrails-types
               "(defn calc [x y] (+ x y))"
               'clojure.core/defn))))

  (testing "returns nil for nil body"
    (is (nil? (ana/extract-guardrails-types nil 'com.fulcrologic.guardrails.core/>defn)))))

;; -----------------------------------------------------------------------------
;; parse-docstring-args
;; -----------------------------------------------------------------------------

(deftest parse-docstring-args-test
  (testing "em-dash form"
    (is (= [{:name "x" :desc "the x" :source "docstring"}
            {:name "y" :desc "the y" :source "docstring"}]
           (ana/parse-docstring-args "Adds.\n  x — the x\n  y — the y" ["x" "y"]))))

  (testing "colon form"
    (is (= [{:name "profile" :desc "the profile map" :source "docstring"}]
           (ana/parse-docstring-args "Stuff.\n  profile: the profile map" ["profile"]))))

  (testing "dash form"
    (is (= [{:name "db" :desc "datomic conn" :source "docstring"}]
           (ana/parse-docstring-args "  db - datomic conn" ["db"]))))

  (testing "filters out names not in the arg list"
    (is (= [{:name "x" :desc "yes" :source "docstring"}]
           (ana/parse-docstring-args "x - yes\nbogus - no" ["x"]))))

  (testing "nil/empty inputs"
    (is (nil? (ana/parse-docstring-args nil ["x"])))
    (is (nil? (ana/parse-docstring-args "x - y" [])))))

;; -----------------------------------------------------------------------------
;; arities-summary
;; -----------------------------------------------------------------------------

(deftest arities-summary-test
  (testing "single arity"
    (is (= {:fixed [1] :varargs nil}
           (ana/arities-summary {:fixed-arities #{1}}))))
  (testing "multi-arity"
    (is (= {:fixed [0 1 2] :varargs nil}
           (ana/arities-summary {:fixed-arities #{2 1 0}}))))
  (testing "varargs"
    (is (= {:fixed [] :varargs 2}
           (ana/arities-summary {:fixed-arities #{} :varargs-min-arity 2}))))
  (testing "empty"
    (is (= {:fixed [] :varargs nil}
           (ana/arities-summary {})))))
