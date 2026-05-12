(ns scripts.instructions.gaps
  "Report orphan and foundation docs — useful for spotting missing or
   overly-foundational instructions.

   - Orphans:   docs with no outgoing AND no incoming edges. Likely
                disconnected from the rest of the corpus.
   - Foundations: docs that are listed as `prereq` by many others but have
                  no outgoing prereq themselves."
  (:require
   [clojure.string :as str]
   [scripts.instructions.db :as idb]
   [scripts.lib.db :as basedb]))

(def default-db-path
  (or (System/getenv "INSTRUCTIONS_DB")
      ".code-intelligence/instructions.db"))

(defn report
  [{:keys [db] :or {db default-db-path}}]
  (idb/open db)
  (let [orphans
        (basedb/query db
                      "SELECT d.path, d.title
                         FROM docs d
                         LEFT JOIN edges eo ON eo.src_path = d.path
                         LEFT JOIN edges ei ON ei.dst_path = d.path
                        WHERE eo.src_path IS NULL AND ei.dst_path IS NULL
                        ORDER BY d.path")
        foundations
        (basedb/query db
                      "SELECT e.dst_path AS path,
                              (SELECT title FROM docs d WHERE d.path = e.dst_path) AS title,
                              COUNT(*) AS n_prereq_of
                         FROM edges e
                         WHERE e.kind = 'prereq'
                           AND NOT EXISTS (SELECT 1 FROM edges f
                                            WHERE f.src_path = e.dst_path
                                              AND f.kind = 'prereq')
                         GROUP BY e.dst_path
                         ORDER BY n_prereq_of DESC")]
    (println "Orphans (no edges in or out):")
    (if (empty? orphans)
      (println "  (none)")
      (doseq [{:keys [path title]} orphans]
        (println (str "  " path (when title (str "  — " title))))))
    (println)
    (println "Foundations (listed as prereq by N docs, themselves require nothing):")
    (if (empty? foundations)
      (println "  (none)")
      (doseq [{:keys [path title n_prereq_of]} foundations]
        (println (format "  %s (×%d)%s"
                         path n_prereq_of
                         (if title (str "  — " title) "")))))))

(defn -main [& args]
  (let [opts (apply hash-map
                    (mapcat (fn [[k v]]
                              (case k
                                "--db" [:db v]
                                nil))
                            (partition 2 args)))]
    (report opts)))
