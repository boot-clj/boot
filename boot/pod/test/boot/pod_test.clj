(ns boot.pod-test
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [boot.tmpdir TmpDir TmpFileSet])
  (:require [clojure.test    :refer :all]
            [clojure.java.io :as io]
            [boot.file       :as file]
            [boot.tmpdir     :as tmp]
            [boot.pod        :as pod]))

(defn tempdir []
  (.toFile (Files/createTempDirectory "tmpdir" (into-array FileAttribute []))))

(defn make-fs []
  (let [dir (tempdir)]
    {:dir dir
     :fs  (TmpFileSet. [(tmp/map->TmpDir {:dir dir})] {} (tempdir) {})}))

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

(deftest lazy-pod-return-value-test-683
  (testing "println inside lazy seqs does not end up in return value"
    (let [data       (range 400)
          expected   (map #(do (println %) %) data)
          pod-result (boot.pod/with-eval-in (boot.pod/make-pod {})
                       (map #(do (println %) %) ~data))]
      (= data pod-result))))

(deftest canonical
  (testing "boot.pod/canonical-id"
    (is (= 'foo (pod/canonical-id 'foo)) "In case there is no group, return artifact")
    (is (= 'foo (pod/canonical-id 'foo/foo)) "In case group and artifact are the same, return only one of them")
    (is (= 'foo/bar (pod/canonical-id 'foo/bar)) "In case group and artifact are the different, return the entire symbol")))
