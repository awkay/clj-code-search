(ns scripts.doctor
  "Environment check + interactive auto-install on macOS/homebrew."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

(defn- on-path? [cmd]
  (try (-> (p/sh ["which" cmd] {:out :string :err :string}) :exit (= 0))
       (catch Exception _ false)))

(defn- prompt-yn [msg]
  (print (str msg " [y/N]: ")) (flush)
  (let [a (read-line)]
    (boolean (some-> a str/trim str/lower-case (= "y")))))

(defn- run! [args]
  (println "  $" (str/join " " args))
  (zero? (:exit (p/shell {:continue true} (str/join " " args)))))

(defn check
  "Returns {:ok? :missing #{...}}."
  []
  (let [missing (->> ["clj-kondo" "claude" "bb"]
                     (remove on-path?)
                     set)]
    {:ok? (empty? missing) :missing missing}))

(defn ensure!
  "Interactive: check the env, offer to install anything missing.
   Returns true iff all prerequisites are present."
  []
  (let [{:keys [ok? missing]} (check)]
    (if ok?
      (do (println "doctor: env OK (clj-kondo, claude, bb)") true)
      (do
        (println "Missing tools:" (str/join ", " missing))
        (when (and (missing "clj-kondo") (prompt-yn "Install clj-kondo via brew?"))
          (run! ["brew" "install" "clj-kondo"]))
        (when (missing "claude")
          (println "claude CLI is not installed. Install Claude Code from https://claude.com/download"))
        (:ok? (check))))))

(defn -main [& _]
  (when-not (ensure!) (System/exit 1)))
