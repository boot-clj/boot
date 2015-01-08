(ns boot.file-test
  (:refer-clojure :exclude [sync file-seq])
  (:require
    [clojure.test :refer :all]
    [boot.file :as file :refer :all :exclude [name]]
    [clojure.java.io :as io]))

(def test-dir (.getParentFile (io/file "public/js/main.js")))
(def abs-dir  (.getParentFile (io/file "/foo/bar/main.js")))

(deftest parent-seq-test
  (let [f (io/file "public/js/main.js")]
    (is (= (seq [f (parent f) (parent (parent f))]) (parent-seq f)))))

(deftest parent?-test
  (is (parent? test-dir (io/file "public/js/out/goog/base.js")))
  (is (not (parent? test-dir (io/file "foo/js/public/out/goog/base.js")))))

(deftest relative-to-test
  (testing "File inside base"
    (is (= "out/goog/base.js"          (str (relative-to test-dir (io/file "public/js/out/goog/base.js"))))))

  (testing "File not inside base"
    (is (= "../../cljsjs/dev/react.js" (str (relative-to test-dir (io/file "cljsjs/dev/react.js"))))))

  (testing "File not inside base"
    (is (= "../cljsjs/dev/react.js"    (str (relative-to test-dir (io/file "public/cljsjs/dev/react.js"))))))

  (testing "Two absolute paths"
    (is (= "js/test.js"                (str (relative-to abs-dir  (io/file "/foo/bar/js/test.js"))))))
  )
