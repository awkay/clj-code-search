(ns scripts.lib.sha
  "Source-text fingerprinting. Ported from fulcro-spec/signature.clj
   (normalize-content + sha256 + leaf/non-leaf composition)."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest)
           (java.nio.charset StandardCharsets)))

(defn- find-string-end [s idx]
  (loop [i idx]
    (when (< i (count s))
      (let [ch (get s i)]
        (cond
          (= ch \\) (recur (+ i 2))
          (= ch \") (inc i)
          :else     (recur (inc i)))))))

(defn- skip-whitespace [s idx]
  (loop [i idx]
    (if (and (< i (count s))
             (Character/isWhitespace (char (get s i))))
      (recur (inc i))
      i)))

(defn- find-matching-bracket [s idx]
  (let [open-ch  (get s idx)
        close-ch (case open-ch \[ \] \( \) \{ \} nil)]
    (when close-ch
      (loop [i (inc idx) depth 1]
        (when (< i (count s))
          (let [ch (get s i)]
            (cond
              (= ch \")
              (if-let [end (find-string-end s (inc i))]
                (recur end depth)
                nil)
              (= ch open-ch)  (recur (inc i) (inc depth))
              (= ch close-ch) (if (= depth 1) (inc i) (recur (inc i) (dec depth)))
              :else           (recur (inc i) depth))))))))

(defn remove-docstring-from-def
  "Strip docstrings from (def... name \"doc\" ...) and (def... name [args] \"doc\" ...) forms."
  [s]
  (let [len (count s)]
    (loop [i 0 result (StringBuilder.)]
      (if (>= i len)
        (str result)
        (let [ch (get s i)]
          (cond
            (= ch \")
            (if-let [end (find-string-end s (inc i))]
              (do (.append result (subs s i end)) (recur end result))
              (recur (inc i) result))

            (and (= ch \()
                 (< (inc i) len)
                 (let [n (get s (inc i))] (or (= n \d) (= n \>))))
            (let [def-start (if (= \> (get s (inc i))) (+ i 2) (inc i))
                  def-end   (loop [j def-start]
                              (if (and (< j len)
                                       (let [c (get s j)]
                                         (or (Character/isLetterOrDigit (char c))
                                             (= c \-) (= c \>))))
                                (recur (inc j)) j))]
              (if (and (>= (- def-end def-start) 3)
                       (str/starts-with? (subs s def-start (min (+ def-start 3) len)) "def")
                       (< def-end len)
                       (Character/isWhitespace (get s def-end)))
                (let [after-def  (skip-whitespace s def-end)
                      name-end   (loop [j after-def]
                                   (if (and (< j len)
                                            (let [c (get s j)]
                                              (not (Character/isWhitespace (char c)))))
                                     (recur (inc j)) j))
                      after-name (skip-whitespace s name-end)]
                  (cond
                    (and (< after-name len) (= \" (get s after-name)))
                    (if-let [doc-end (find-string-end s (inc after-name))]
                      (do (.append result (subs s i after-name)) (recur doc-end result))
                      (do (.append result ch) (recur (inc i) result)))

                    (and (< after-name len) (= \[ (get s after-name)))
                    (if-let [args-end (find-matching-bracket s after-name)]
                      (let [after-args (skip-whitespace s args-end)]
                        (if (and (< after-args len) (= \" (get s after-args)))
                          (if-let [doc-end (find-string-end s (inc after-args))]
                            (do (.append result (subs s i after-args)) (recur doc-end result))
                            (do (.append result ch) (recur (inc i) result)))
                          (do (.append result ch) (recur (inc i) result))))
                      (do (.append result ch) (recur (inc i) result)))

                    :else
                    (do (.append result ch) (recur (inc i) result))))
                (do (.append result ch) (recur (inc i) result))))

            :else
            (do (.append result ch) (recur (inc i) result))))))))

(defn normalize-content
  "Strips docstrings, collapses whitespace, trims. Returns nil for nil input."
  [source-text]
  (when source-text
    (try
      (-> source-text
          remove-docstring-from-def
          (str/replace #"\s+" " ")
          str/trim)
      (catch Exception _ source-text))))

(defn sha256
  "SHA256 hex digest over UTF-8 bytes of s."
  [^String s]
  (when s
    (let [d (MessageDigest/getInstance "SHA-256")
          b (.digest d (.getBytes s StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" %) b)))))

(defn hash-content
  "Normalize + sha256. Full 64-char hex."
  [source-text]
  (some-> source-text normalize-content sha256))

(defn short-sig
  "First 6 chars of full hex."
  [hex]
  (when hex (subs hex 0 (min 6 (count hex)))))

(defn self-sig
  "6-char signature of a function's own normalized body."
  [body-source]
  (short-sig (hash-content body-source)))

(defn composite-sig
  "Leaf: \"selfhex\". Non-leaf: \"selfhex,callees6\" where callees6 is sha of
   sorted callee-sig strings joined by commas."
  [self-6 callee-sigs]
  (let [callee-sigs (->> callee-sigs (remove nil?) sort)]
    (if (seq callee-sigs)
      (str self-6 "," (short-sig (sha256 (str/join "," callee-sigs))))
      self-6)))
