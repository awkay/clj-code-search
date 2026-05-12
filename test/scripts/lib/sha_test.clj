(ns scripts.lib.sha-test
  "Ported from fulcro-spec/src/test/fulcro_spec/signature_spec.clj
   (assertions/specification/behavior → clojure.test)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scripts.lib.sha :as sha]))

(def sample-source
  "(defn calculate
  \"Calculates something.\"
  [x y]
  (+ x y))")

;; -----------------------------------------------------------------------------
;; normalize-content
;; -----------------------------------------------------------------------------

(deftest normalize-content-test
  (testing "strips docstrings from def forms"
    (let [r (sha/normalize-content sample-source)]
      (is (string? r))
      (is (not (str/includes? r "Calculates")))
      (is (str/includes? r "defn"))
      (is (str/includes? r "calculate"))
      (is (str/includes? r "+ x y"))))

  (testing "same hash regardless of docstring position"
    (let [a "(defn calculate \"Doc\" [x y] (+ x y))"
          b "(defn calculate [x y] \"Doc\" (+ x y))"
          c "(defn calculate [x y] (+ x y))"]
      (is (= (sha/hash-content a) (sha/hash-content b)))
      (is (= (sha/hash-content b) (sha/hash-content c)))))

  (testing "handles docstrings with escaped quotes"
    (let [with "(defn foo \"This is a \\\"docstring\\\" with quotes\" [x] (+ x 1))"
          w/o  "(defn foo [x] (+ x 1))"
          r    (sha/normalize-content with)]
      (is (not (str/includes? r "docstring")))
      (is (str/includes? r "(defn foo [x] (+ x 1))"))
      (is (= (sha/hash-content with) (sha/hash-content w/o)))))

  (testing "handles multiline docstrings"
    (let [m   "(defn bar\n  \"This is a docstring\n  that spans multiple\n  lines with details\"\n  [x y]\n  (* x y))"
          w/o "(defn bar [x y] (* x y))"]
      (is (= (sha/hash-content m) (sha/hash-content w/o)))))

  (testing "preserves string literals that are not docstrings"
    (let [r (sha/normalize-content "(defn greet [name] (str \"Hello, \" name))")]
      (is (str/includes? r "\"Hello, \""))
      (is (str/includes? r "name"))))

  (testing "preserves source text exactly (no reader expansion)"
    (let [r (sha/normalize-content "(p `get-entity-min-issue-date (foo))")]
      (is (str/includes? r "`get-entity-min-issue-date"))))

  (testing "deterministic for syntax-quoted symbols"
    (let [code "(p `get-entity-min-issue-date (foo))"]
      (is (= (sha/hash-content code) (sha/hash-content code)))))

  (testing "deterministic for anonymous functions"
    (let [code "(def underscore #(str/replace % #\"-\" \"_\"))"]
      (is (= (sha/hash-content code) (sha/hash-content code)))))

  (testing "nil input"
    (is (nil? (sha/normalize-content nil))))

  (testing "falls back to original on processing errors"
    (let [invalid "(defn broken [x"]
      (is (= invalid (sha/normalize-content invalid)))))

  (testing ">defn (guardrails) syntax"
    (let [g   "(>defn calculate \"Doc for guardrails\" [x y] [int? int? => int?] (+ x y))"
          w/o "(>defn calculate [x y] [int? int? => int?] (+ x y))"
          r   (sha/normalize-content g)]
      (is (not (str/includes? r "Doc for guardrails")))
      (is (str/includes? r ">defn calculate"))
      (is (= (sha/hash-content g) (sha/hash-content w/o))))))

;; -----------------------------------------------------------------------------
;; sha256
;; -----------------------------------------------------------------------------

(deftest sha256-test
  (testing "hex string of length 64"
    (let [r (sha/sha256 "hello world")]
      (is (string? r))
      (is (= 64 (count r)))
      (is (re-matches #"[0-9a-f]+" r))))
  (testing "consistent for same input"
    (is (= (sha/sha256 "test") (sha/sha256 "test"))))
  (testing "different for different input"
    (is (not= (sha/sha256 "test1") (sha/sha256 "test2"))))
  (testing "nil input"
    (is (nil? (sha/sha256 nil)))))

;; -----------------------------------------------------------------------------
;; hash-content
;; -----------------------------------------------------------------------------

(deftest hash-content-test
  (testing "combines normalization and hashing"
    (let [with "(defn calculate \"Doc\" [x y] (+ x y))"
          w/o  "(defn calculate [x y] (+ x y))"]
      (is (string? (sha/hash-content with)))
      (is (= (sha/hash-content with) (sha/hash-content w/o)))))

  (testing "ignores whitespace differences"
    (let [a "(defn foo [x] (* x 2))"
          b "(defn foo [x]  (*  x  2))"
          c "(defn foo\n  [x]\n  (* x 2))"
          d "(defn\tfoo\t[x]\t(*\tx\t2))"]
      (is (= (sha/hash-content a) (sha/hash-content b)))
      (is (= (sha/hash-content b) (sha/hash-content c)))
      (is (= (sha/hash-content c) (sha/hash-content d)))))

  (testing "detects logic changes"
    (is (not= (sha/hash-content "(defn foo [x] (* x 2))")
              (sha/hash-content "(defn foo [x] (* x 3))"))))

  (testing "nil input"
    (is (nil? (sha/hash-content nil)))))

;; -----------------------------------------------------------------------------
;; edge cases
;; -----------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "deeply nested structures preserve string literals + keywords"
    (let [r (sha/normalize-content
             "(defn complex [x]
                 (let [a (fn [y]
                           (let [b (fn [z]
                                     {:key \"value\"})]
                             (b y)))]
                   (a x)))")]
      (is (str/includes? r "\"value\""))
      (is (str/includes? r ":key"))))

  (testing "multiple def forms — both docstrings removed"
    (let [r (sha/normalize-content
             "(defn first-fn \"Doc1\" [x] x)\n(defn second-fn \"Doc2\" [y] y)")]
      (is (not (str/includes? r "Doc1")))
      (is (not (str/includes? r "Doc2")))
      (is (str/includes? r "first-fn"))
      (is (str/includes? r "second-fn"))))

  (testing "defn- (private)"
    (is (= (sha/hash-content "(defn- private-helper \"Private doc\" [x] (inc x))")
           (sha/hash-content "(defn- private-helper [x] (inc x))"))))

  (testing "def (not just defn)"
    (let [r (sha/normalize-content "(def my-constant \"A constant value\" 42)")]
      (is (not (str/includes? r "A constant value")))
      (is (str/includes? r "my-constant"))
      (is (str/includes? r "42"))))

  (testing "empty function bodies"
    (let [r (sha/normalize-content "(defn noop \"Does nothing\" [])")]
      (is (not (str/includes? r "Does nothing")))
      (is (str/includes? r "defn noop")))))

;; -----------------------------------------------------------------------------
;; composite-sig (our addition: leaf vs non-leaf composition)
;; -----------------------------------------------------------------------------

(deftest composite-sig-test
  (let [self (sha/self-sig "(defn foo [x] (* x 2))")]
    (testing "leaf returns single 6-char field"
      (let [sig (sha/composite-sig self nil)]
        (is (= 6 (count sig)))
        (is (= self sig))))
    (testing "non-leaf returns 'self,callees6'"
      (let [sig (sha/composite-sig self ["aaa111" "bbb222"])]
        (is (= 13 (count sig)))
        (is (str/starts-with? sig (str self ",")))))
    (testing "callee order doesn't matter (we sort)"
      (is (= (sha/composite-sig self ["aaa111" "bbb222"])
             (sha/composite-sig self ["bbb222" "aaa111"]))))
    (testing "different callees produce different sigs"
      (is (not= (sha/composite-sig self ["aaa111"])
                (sha/composite-sig self ["bbb222"]))))))
