(ns tailrecursion.boot.gitignore
  (:refer-clojure :exclude [replace])
  (:require
    [clojure.java.shell :refer [sh]]
    [clojure.java.io    :refer [file]]
    [clojure.string     :refer [blank? join split replace trim]])
  (:import
    [java.nio.file FileSystems]))

(defprotocol IMatcher
  (-negated? [this] "Is this pattern negated?")
  (-matches? [this f] "Does file f match this pattern?"))

(defrecord Matcher [negated? matcher]
  IMatcher
  (-negated? [this] negated?)
  (-matches? [this f] (matcher f)))

(defn matches? [matchers f]
  (loop [match? nil, [matcher & more-matchers] matchers]
    (if-not matcher
      match?
      (let [m?  (-matches? matcher f)
            n?  (-negated? matcher)] 
        (recur (if (not m?) match? (not n?)) more-matchers)))))

(defn path-matcher [pattern & [negated?]]
  (let [m (.. FileSystems (getDefault) (getPathMatcher (str "glob:" pattern)))]
    (Matcher. negated? (fn [f] (.matches m (.toPath (.getCanonicalFile f)))))))

(defn parse-gitignore1 [pattern base]
  (let [base    (if (.endsWith base "/") base (str base "/"))
        strip   #(replace % #"^/*" "")
        pat     (atom pattern)
        mat     (atom [])
        [negated? end-slash? has-slash? lead-slash? lead-asts? end-ast?] 
        (map #(fn [] (re-find % @pat)) [#"^!" #"/$" #"/" #"^/" #"^\*\*/" #"/\*$"])
        neg?    (negated?)
        dir?    (end-slash?)
        matcher #(path-matcher (apply str base %&))]
    (when (negated?) (swap! pat replace #"^!" ""))
    (when (end-slash?) (swap! pat replace #"/*$" ""))
    (if-not (has-slash?) 
      (swap! mat into (map matcher [@pat (str "**/" @pat)]))
      (swap! mat conj (matcher (strip @pat))))
    (when (lead-asts?)
      (swap! mat conj (matcher (strip (subs @pat 3)))))
    (when (end-ast?)
      (swap! mat conj (matcher (strip @pat) "*")))
    (Matcher. neg?  (fn [f] (and (or (not dir?) (.isDirectory f))
                                 (some #(-matches? % f) @mat))))))

(defn parse-gitignore [f & [base]]
  (let [base  (or base (-> f file (.getCanonicalFile) (.getParent))) 
        skip? #(or (blank? %) (re-find #"^\s*#" %))
        lines (->> f slurp (#(split % #"\n")) (remove skip?) (map trim))]
    (map parse-gitignore1 lines (repeat base))))

(defn core-excludes [& [$GIT_DIR]]
  (let [git #(sh "git" "config" "core.excludesfile")
        cwd (or $GIT_DIR (System/getProperty "user.dir"))]
    (try (-> (git) :out trim file (parse-gitignore cwd)) (catch Throwable _))))

(defn make-gitignore-matcher [& [$GIT_DIR]]
  (let [cwd         (or $GIT_DIR (System/getProperty "user.dir")) 
        gi-file?    #(= ".gitignore" (.getName %))
        gitignores  (->> (file cwd) file-seq (filter gi-file?))
        core-excl   (vec (or (core-excludes $GIT_DIR) []))
        matchers    (into core-excl (mapcat parse-gitignore gitignores))]
    (partial matches? matchers)))
