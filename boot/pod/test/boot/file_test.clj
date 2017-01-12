(ns boot.file-test
  (:refer-clojure :exclude [sync file-seq])
  (:require
    [clojure.test :refer :all]
    [boot.file :as file :refer :all :exclude [name]]
    [clojure.java.io :as io]))

(def test-file (io/file "public/js/main.js"))
(def test-dir (parent test-file))
(def abs-dir  (parent (io/file "/foo/bar/main.js")))

(deftest parent-seq-test
  (let [f test-file]
    (is (= (seq [f (parent f) (parent (parent f))]) (parent-seq f)))))

(deftest split-path-test
  (is (= (seq ["public" "js" "main.js"]) (split-path test-file))))

(deftest parent?-test
  (is (parent? test-dir (io/file "public/js/out/goog/base.js")))
  (is (not (parent? test-dir (io/file "foo/js/public/out/goog/base.js")))))

(deftest relative-to-test
  (testing "Nil base"
    (is (thrown? AssertionError        (str (relative-to nil      (io/file "out/goog/base.js"))))))

  (testing "File inside base"
    (is (= "out/goog/base.js"          (str (relative-to test-dir (io/file "public/js/out/goog/base.js"))))))

  (testing "File not inside base"
    (is (= "../../cljsjs/dev/react.js" (str (relative-to test-dir (io/file "cljsjs/dev/react.js"))))))

  (testing "File not inside base"
    (is (= "../cljsjs/dev/react.js"    (str (relative-to test-dir (io/file "public/cljsjs/dev/react.js"))))))

  (testing "Two absolute paths"
    (is (= "js/test.js"                (str (relative-to abs-dir  (io/file "/foo/bar/js/test.js"))))))
  )

(deftest match-filter?-test
  (let [filters #{#"^META-INF/MANIFEST.MF$"}]
    (testing "Unix-style paths"
      (is (match-filter? filters (io/file "META-INF/MANIFEST.MF"))))
    (testing "Windows-style paths"
      (binding [windows? true]
        (is (match-filter? filters (io/file "META-INF\\MANIFEST.MF")))))
    (testing "Sanity check for failure"
      (is (not (match-filter? filters (io/file "META-INF/MANIFEST.NO")))))))
