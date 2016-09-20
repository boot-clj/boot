(ns boot.deps-util-test
  (:require
    [clojure.test :refer :all]
    [boot.deps-util :refer :all]
    [boot.util :as util]))

(def ^:const test-deps '[[alpha/beta "1.0.0"]
                         [gamma/delta "2.0.0" :a "a" :scope "test"]
                         [test/excl "1.2.3" :exclusions [a b c d]]
                         [foo/foo "4.5.6" :scope "test"]
                         [bar/bar "7.8.9" :extension "zip"]
                         [test/cls "7.8.9" :extension "zip" :classifier "other"]])

(def ^:const test-indexed-map {['alpha/beta "jar"]       {:project   'alpha/beta
                                                          :version   "1.0.0"
                                                          :extension "jar"
                                                          :scope     "compile"}
                               ['gamma/delta "jar"]      {:project   'gamma/delta
                                                          :version   "2.0.0"
                                                          :extension "jar"
                                                          :scope     "test"
                                                          :a         "a"}
                               ['test/excl "jar"]        {:project    'test/excl
                                                          :version    "1.2.3"
                                                          :extension  "jar"
                                                          :scope      "compile"
                                                          :exclusions '[a b c d]}
                               ['foo "jar"]              {:project   'foo
                                                          :version   "4.5.6"
                                                          :extension "jar"
                                                          :scope     "test"}
                               ['bar "zip"]              {:project   'bar
                                                          :version   "7.8.9"
                                                          :extension "zip"
                                                          :scope     "compile"}
                               ['test/cls "zip" "other"] {:project    'test/cls
                                                          :version    "7.8.9"
                                                          :extension  "zip"
                                                          :classifier "other"
                                                          :scope      "compile"}})

(deftest check-maybe-strip-scope
  (let [maybe-strip-scope @#'boot.deps-util/maybe-strip-scope]
    (are [scope? dep] (= scope? (->> dep
                                     util/dep-as-map
                                     (maybe-strip-scope dep)
                                     :scope
                                     boolean))
                      true '[alpha/beta "1.0.0" :scope "test"]
                      false '[alpha/beta "1.0.0"])))

(deftest check-dep-key
  (let [dep-key @#'boot.deps-util/dep-key]
    (are [input key] (= key (dep-key input))

                     {}
                     [nil "jar"]

                     {:project 'foo}
                     ['foo "jar"]

                     {:project   'foo
                      :extension "zip"}
                     ['foo "zip"]

                     {:project    'foo
                      :extension  "jar"
                      :classifier "other"}
                     ['foo "jar" "other"])))

(deftest check-managed-deps-map
  (let [managed-deps-map @#'boot.deps-util/managed-deps-map]
    (is (= {} (managed-deps-map nil)))
    (is (= test-indexed-map (managed-deps-map test-deps)))))

(deftest check-complete-dep
  (let [complete-dep @#'boot.deps-util/complete-dep
        managed-deps-map @#'boot.deps-util/managed-deps-map
        managed-deps (managed-deps-map test-deps)
        f (partial complete-dep managed-deps)]
    ;; must compare maps because option order is not stable in vectors
    (are [x y] (= (util/dep-as-map x) (util/dep-as-map (f y)))
               '[alpha/beta "1.0.0"] '[alpha/beta]
               '[alpha/beta "1.0.0" :b "b"] '[alpha/beta nil :b "b"]
               '[gamma/delta "2.0.0" :scope "test" :a "a"] '[gamma/delta]
               '[test/excl "1.2.3" :exclusions [a b c d]] '[test/excl])))

(deftest check-complete-deps
  (let [managed-deps-map @#'boot.deps-util/managed-deps-map
        complete-deps @#'boot.deps-util/complete-deps
        managed-deps (managed-deps-map test-deps)]
    (is (= [] (complete-deps managed-deps nil)))
    (is (= [] (complete-deps nil nil)))

    ;; must compare maps because option order is not stable in vectors
    (are [x y] (= (map util/dep-as-map x) (map util/dep-as-map (complete-deps managed-deps y)))
               '[[alpha/beta "1.0.0" :b "b"]
                 [gamma/delta "2.0.0" :scope "test" :a "a"]]
               '[[alpha/beta nil :b "b"]
                 [gamma/delta]]

               '[[gamma/delta "2.0.0" :scope "test" :a "a"]
                 [alpha/beta "1.0.0" :b "b"]]
               '[[gamma/delta]
                 [alpha/beta nil :b "b"]]

               '[[gamma/delta "2.0.0" :a "a"]
                 [alpha/beta "1.0.0" :b "b"]]
               '[[gamma/delta nil :scope "compile"]
                 [alpha/beta nil :b "b"]]

               '[[gamma/delta "2.0.0" :scope "test" :a "a"]
                 [alpha/beta "1.0.0" :b "b"]
                 [alpha/omega]]
               '[[gamma/delta]
                 [alpha/beta nil :b "b"]
                 [alpha/omega]]

               '[[alpha/omega]
                 [test/excl "1.2.3" :exclusions [a b c d]]]
               '[[alpha/omega]
                 [test/excl]])))

(deftest check-merge-deps
  (is (= [] (merge-deps nil test-deps)))
  (is (= [] (merge-deps nil nil)))

  ;; must compare maps because option order is not stable in vectors
  (are [x y] (= (map util/dep-as-map x) (map util/dep-as-map (merge-deps y test-deps)))
             '[[alpha/beta "1.0.0" :b "b"]
               [gamma/delta "2.0.0" :scope "test" :a "a"]]
             '[[alpha/beta nil :b "b"]
               [gamma/delta]]

             '[[gamma/delta "2.0.0" :scope "test" :a "a"]
               [alpha/beta "1.0.0" :b "b"]]
             '[[gamma/delta]
               [alpha/beta nil :b "b"]]

             '[[gamma/delta "2.0.0" :a "a"]
               [alpha/beta "1.0.0" :b "b"]]
             '[[gamma/delta nil :scope "compile"]
               [alpha/beta nil :b "b"]]

             '[[gamma/delta "2.0.0" :scope "test" :a "a"]
               [alpha/beta "1.0.0" :b "b"]
               [alpha/omega]]
             '[[gamma/delta]
               [alpha/beta nil :b "b"]
               [alpha/omega]]

             '[[alpha/omega]
               [test/excl "1.2.3" :exclusions [a b c d]]]
             '[[alpha/omega]
               [test/excl]]))

(deftest check-read-deps
  (is (= [['example/project1 "1.0.0" :scope "test"]
          ['example/project2 "2.0.0" :scope "compile"]]
         (read-deps nil nil)))
  (is (= [['example/project3 "3.0.0" :scope "test"]
          ['example/project4 "4.0.0" :scope "compile"]]
         (read-deps nil "managed-dependencies.edn")))
  (is (nil? (read-deps nil "unknown-file.edn")))
  (is (thrown? Exception (read-deps nil "bad-dependencies.edn"))))
