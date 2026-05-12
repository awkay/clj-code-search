(ns scripts.lib.kondo-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scripts.lib.kondo :as kondo]))

(def fixture-path "test/fixtures/sample.cljc")

(defn- by-name [recs sym]
  (first (filter #(= sym (:name %)) recs)))

(deftest analyze-test
  (testing "analyze produces one record per defn in a CLJC file (merged to lang=cljc)"
    (let [recs (kondo/analyze fixture-path)]
      (is (= 4 (count recs)) "add / double-it / use-add / save!")
      (is (every? #(= "cljc" (:lang %)) recs)
          "all four fns have identical clj+cljs bodies and should collapse")))

  (testing "records carry deterministic fields"
    (let [add (by-name (kondo/analyze fixture-path) 'add)]
      (is (= 'scripts.test.fixtures.sample (:ns add)))
      (is (= "scripts.test.fixtures.sample/add" (:qualified-name add)))
      (is (= "Adds two numbers." (:docstring add)))
      (is (= "([x y])" (:arglists-edn add)))
      (is (= {:fixed [2] :varargs nil} (:arities add)))
      (is (= 'clojure.core/defn (:defined-by add)))
      (is (not (:private? add)))
      (is (true? (:pure-heuristic? add)))
      (is (some? (:body add)))
      (is (str/includes? (:body add) "(+ x y)"))))

  (testing "save! is detected as impure"
    (let [save (by-name (kondo/analyze fixture-path) 'save!)]
      (is (false? (:pure-heuristic? save)))
      (is (seq (:pure-heuristic-reasons save)))))

  (testing "caller mining: use-add appears as a caller of add"
    (let [recs (kondo/analyze fixture-path)
          add  (by-name recs 'add)]
      (is (pos? (:caller-count add)))
      (is (some #(= 'scripts.test.fixtures.sample (:from %))
                (map (fn [c]
                       {:from (-> (:in c)
                                  (str/split #"/")
                                  first
                                  symbol)})
                     (:example-callers add))))
      (is (every? string? (map :snippet (:example-callers add))))))

  (testing "no callers for unused defn (save!)"
    (let [save (by-name (kondo/analyze fixture-path) 'save!)]
      (is (zero? (:caller-count save))))))

(deftest skip-filename-test
  (testing ":skip-filename? option drops matching files"
    (let [recs (kondo/analyze fixture-path
                              {:skip-filename? (fn [f] (str/includes? f "sample.cljc"))})]
      (is (empty? recs)))))

(deftest file-sha-test
  (testing "file-sha is deterministic"
    (let [a (kondo/file-sha fixture-path)
          b (kondo/file-sha fixture-path)]
      (is (string? a))
      (is (= 64 (count a)))
      (is (= a b)))))

(deftest extract-body-test
  (testing "extract-body slices the source using kondo positions"
    (let [recs (kondo/analyze fixture-path)
          add  (by-name recs 'add)]
      (is (str/starts-with? (:body add) "(defn add"))
      (is (str/ends-with? (:body add) ")")))))
