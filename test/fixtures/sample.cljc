(ns scripts.test.fixtures.sample
  "A small CLJC namespace used as a clj-kondo fixture in tests.")

(defn add
  "Adds two numbers."
  [x y]
  (+ x y))

(defn double-it
  [x]
  (* x 2))

(defn use-add
  "Calls `add` so caller-mining has something to find."
  [a b]
  (let [s (add a b)]
    (double-it s)))

(defn save!
  "Side-effecting: bumps an atom."
  [counter v]
  (swap! counter assoc :v v))
