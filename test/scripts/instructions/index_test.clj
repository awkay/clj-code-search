(ns scripts.instructions.index-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [scripts.instructions.db :as idb]
   [scripts.lib.db :as basedb]))

(def ^:dynamic *db* nil)

(defn- tmp-db-path []
  (str (System/getProperty "java.io.tmpdir") "/inst-test-"
       (System/currentTimeMillis) "-" (rand-int 100000) ".db"))

(defn- fresh-db [f]
  (let [path (tmp-db-path)]
    (try
      (idb/open path)
      (binding [*db* path] (f))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (io/delete-file (str path suffix) true))))))

(use-fixtures :each fresh-db)

(deftest schema-applied
  (testing "open creates docs, edges, FTS tables and triggers"
    (let [names (->> (basedb/query *db* "SELECT name FROM sqlite_master ORDER BY name")
                     (map :name) set)]
      (is (contains? names "docs"))
      (is (contains? names "edges"))
      (is (contains? names "docs_fts"))
      (is (contains? names "docs_ai"))
      (is (contains? names "docs_ad"))
      (is (contains? names "docs_au")))))

(deftest doc-upsert-sha-cache
  (testing "first insert returns :inserted; identical re-insert :unchanged"
    (let [d {:path "a.md" :sha "sha1" :root-dir "."
             :title "A" :summary "s" :explains ["e1"] :tags ["t"]
             :body "body" :model "haiku"}]
      (is (= :inserted (idb/upsert-doc! *db* d)))
      (is (= :unchanged (idb/upsert-doc! *db* d)))
      (is (= :updated (idb/upsert-doc! *db* (assoc d :sha "sha2"))))))
  (testing "stored fields round-trip"
    (let [row (idb/get-doc *db* "a.md")]
      (is (= "A" (:title row)))
      (is (= ["e1"] (idb/parse-explains row)))
      (is (= ["t"]  (idb/parse-tags row))))))

(deftest edges-replace-transactional
  (testing "replace-edges! installs and overwrites edges atomically"
    (doseq [p ["a.md" "b.md" "c.md" "d.md"]]
      (idb/upsert-doc! *db* {:path p :sha (str "sha-" p) :root-dir "."
                             :title p :summary p :explains [] :tags []
                             :body  p :model "haiku"}))
    (idb/replace-edges! *db* "a.md"
                        [{:dst "b.md" :kind "prereq"    :confidence 0.9}
                         {:dst "c.md" :kind "companion" :confidence 0.7}])
    (is (= 2 (count (idb/outgoing-edges *db* "a.md"))))
    (is (= 1 (count (idb/incoming-edges *db* "b.md"))))
    (testing "replacement drops old edges"
      (idb/replace-edges! *db* "a.md"
                          [{:dst "d.md" :kind "extends" :confidence 0.8}])
      (let [out (idb/outgoing-edges *db* "a.md")]
        (is (= 1 (count out)))
        (is (= "d.md" (:dst_path (first out)))))
      (is (zero? (count (idb/incoming-edges *db* "b.md")))))
    (testing "self-edges are ignored"
      (idb/replace-edges! *db* "a.md"
                          [{:dst "a.md" :kind "companion" :confidence 1.0}
                           {:dst "b.md" :kind "prereq"    :confidence 0.6}])
      (is (= ["b.md"] (mapv :dst_path (idb/outgoing-edges *db* "a.md")))))))

(deftest fts-candidates-finds-by-explains
  (testing "FTS candidate lookup retrieves docs by their explains text"
    (idb/upsert-doc! *db* {:path "target.md" :sha "x" :root-dir "."
                           :title "T" :summary "writing a frob"
                           :explains ["frob a widget"] :tags []
                           :body "" :model "haiku"})
    (idb/upsert-doc! *db* {:path "frob.md" :sha "y" :root-dir "."
                           :title "Frob" :summary "explains frob"
                           :explains ["frob a widget"] :tags []
                           :body "frob body" :model "haiku"})
    (idb/upsert-doc! *db* {:path "unrelated.md" :sha "z" :root-dir "."
                           :title "U" :summary "totally unrelated zigzag"
                           :explains ["zigzag"] :tags []
                           :body "" :model "haiku"})
    (let [cands (idb/fts-candidates *db* "target.md" "frob widget" 5)
          paths (map :path cands)]
      (is (contains? (set paths) "frob.md"))
      (is (not (contains? (set paths) "target.md"))))))

(deftest candidates-sha-cache
  (testing "set-candidates-sha! round-trips and is observable on doc row"
    (idb/upsert-doc! *db* {:path "a.md" :sha "s" :root-dir "."
                           :title "A" :summary "" :explains [] :tags []
                           :body "" :model "haiku"})
    (idb/set-candidates-sha! *db* "a.md" "comp-xyz")
    (is (= "comp-xyz" (:candidates_sha (idb/doc-row-by-path *db* "a.md"))))))
