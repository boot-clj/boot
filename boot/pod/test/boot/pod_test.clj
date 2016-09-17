(ns boot.pod-test
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [boot.tmpdir TmpDir TmpFileSet])
  (:require [clojure.test    :refer :all]
            [clojure.java.io :as io]
            [boot.file       :as file]
            [boot.pod        :as pod]
            [boot.tmpdir     :as tmp]))

(defn tempdir []
  (.toFile (Files/createTempDirectory "tmpdir" (into-array FileAttribute []))))

(defn make-fs []
  (let [dir (tempdir)]
    {:dir dir
     :fs  (TmpFileSet. [(tmp/map->TmpDir {:dir dir})] {} (tempdir) (tempdir))}))

(defn spit-to [dir path contents]
  (spit (doto (apply io/file dir path) io/make-parents) contents))

(defn map-fs-contents [fs]
  (let [path-list #(file/split-path (tmp/path %))
        contents  #(slurp (tmp/file %))]
    (->> fs tmp/ls (reduce #(assoc %1 (path-list %2) (contents %2)) {}))))

(deftest fileset-test
  (testing "fileset is a fileset"
    (let [{:keys [dir fs]} (make-fs)]
      (is (tmp/tmpfileset? fs))))

  (testing "initial fileset is empty"
    (let [{:keys [dir fs]} (make-fs)]
      (is (empty? (tmp/ls fs)))))

  (let [{:keys [dir fs]} (make-fs)
        src1   (doto (tempdir)
                 (spit-to ["a"] "foo")
                 (spit-to ["b" "c"] "bar"))
        src2   (doto (tempdir)
                 (spit-to ["b" "c"] "baz"))
        fs     (-> fs (tmp/add dir src1 {}) tmp/commit!)
        before {'("a") "foo" '("b" "c") "bar"}
        after  {'("a") "foo" '("b" "c") "baz"}]

    (testing "fileset with files is not empty"
      (is (not (empty? (tmp/ls fs)))))

    (testing "fileset with files has correct number of them"
      (is (= 2 (count (tmp/ls fs)))))

    (testing "fileset with files has correct paths and contents"
      (is (= before (map-fs-contents fs))))

    (testing "adding dir to fileset can clobber paths"
      (let [fs (-> fs (tmp/add dir src2 {}) tmp/commit!)]
        (is (= after (map-fs-contents fs)))))

    (testing "re-committing fileset restores former contents"
      (let [fs (-> fs tmp/commit!)]
        (is (= before (map-fs-contents fs)))))

    ))

(deftest check-aliases-work
  (let [dep ['foo/bar "1.2.3"]
        dep-map {:project 'foo/bar
                 :version "1.2.3"
                 :extension "jar"
                 :scope "compile"}]
    (is (= dep-map (pod/coord->map dep)))
    (is (= dep (pod/map->coord dep-map)))

    (is (= 'foo (pod/canonical-id 'foo/foo)))
    (is (= 'foo/bar (pod/canonical-id 'foo/bar)))

    (is (= 'foo/foo (pod/full-id 'foo/foo)))
    (is (= 'foo/bar (pod/full-id 'foo/bar)))

    (is (= '[foo "1.2.3"] (pod/canonical-coord '[foo/foo "1.2.3"])))

    (is (= ["foo" "foo"] (pod/extract-ids 'foo)))
    (is (= ["foo" "bar"] (pod/extract-ids 'foo/bar)))))
