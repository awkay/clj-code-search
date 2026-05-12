(ns scripts.instructions.prompts-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scripts.instructions.llm :as llm]))

(deftest pass1-prompt-shape
  (let [p (llm/pass1-prompt
           {:path  "docs/foo.md"
            :title "Foo Guide"
            :body  "# Foo\n\nHow to foo."})]
    (is (str/includes? p "Doc path: docs/foo.md"))
    (is (str/includes? p "Title: Foo Guide"))
    (is (str/includes? p "How to foo"))
    (is (str/includes? p "\"summary\""))
    (is (str/includes? p "\"explains\""))
    (is (str/includes? p "\"tags\""))
    (is (str/includes? p "JSON ONLY"))
    (testing "explicitly forbids listing prereqs"
      (is (str/includes? p "Do not list prerequisites")))))

(deftest pass2-prompt-shape
  (let [p (llm/pass2-prompt
           {:doc        {:path     "docs/target.md"
                         :title    "Target"
                         :summary  "writing a frob"
                         :explains ["frob a widget" "frob a gadget"]
                         :body     "body of target"}
            :candidates [{:path     "docs/a.md"
                          :title    "A"
                          :summary  "summary-a"
                          :explains ["explain-a-1"]}
                         {:path     "docs/b.md"
                          :title    "B"
                          :summary  "summary-b"
                          :explains ["explain-b-1" "explain-b-2"]}]})]
    (is (str/includes? p "TARGET DOC"))
    (is (str/includes? p "docs/target.md"))
    (is (str/includes? p "frob a widget"))
    (is (str/includes? p "docs/a.md"))
    (is (str/includes? p "docs/b.md"))
    (is (str/includes? p "explain-b-2"))
    (testing "enumerates the three edge kinds"
      (is (str/includes? p "prereq"))
      (is (str/includes? p "companion"))
      (is (str/includes? p "extends")))
    (testing "states the do-not-invent rule"
      (is (str/includes? p "do not invent")))))

(deftest edges-from-pass2-filters-and-flattens
  (let [result {:prereq    [{:path "docs/a.md" :confidence 0.9}
                            {:path "docs/missing.md" :confidence 0.95}
                            {:path "docs/b.md" :confidence 0.3}] ; below floor
                :companion [{:path "docs/c.md" :confidence 0.7}]
                :extends   []}
        edges (llm/edges-from-pass2 result
                                    ["docs/a.md" "docs/b.md" "docs/c.md"]
                                    {:confidence-floor 0.5})]
    (testing "drops paths not in candidate list (no invented edges)"
      (is (not-any? #(= "docs/missing.md" (:dst %)) edges)))
    (testing "drops edges below confidence floor"
      (is (not-any? #(= "docs/b.md" (:dst %)) edges)))
    (testing "flattens to {:dst :kind :confidence}"
      (is (= #{{:dst "docs/a.md" :kind "prereq"    :confidence 0.9}
               {:dst "docs/c.md" :kind "companion" :confidence 0.7}}
             (set edges))))))
