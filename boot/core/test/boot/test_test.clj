(ns boot.test-test
  (:require [boot.core :as core]
            [boot.test :refer :all]
            [clojure.test :refer :all]))

(deftesttask macro-splitting-tests
  "Testing the splitting and recomposing algorithm of the deftesttask macro."
  []
  (core/with-pass-thru fileset
    (testing "boot.test/replace-body for null-task V1"
      (let [null-task '(deftask null-task
                         "Does nothing."
                         [a a-option VAL kw "The option."
                          c counter int "The counter."]
                         (fn [_] _))
            null-task-no-doc (remove string? null-task)
            expected-null-task (concat (butlast (rest null-task)) '((comp (fn [_] _))))
            expected-null-task-no-doc (remove string? expected-null-task)]
        (is (= (rest null-task) (update-body (rest null-task) identity)) "It should rebuild the same task form, round-tripping")
        (is (= (rest null-task-no-doc) (update-body (rest null-task-no-doc) identity)) "Even with no docstring it should rebuild the same task form")
        (is (= expected-null-task (update-body (rest null-task) #(cons 'comp (list %)))) "Updating body should work, of course")
        (is (= expected-null-task-no-doc (update-body (rest null-task-no-doc) #(cons 'comp (list %)))) "Updating body should work, of course")))

    (testing "boot.test/replace-body for null-task V2"
      (let [null-task '(deftask null-task
                         "Does nothing."
                         [a a-option VAL kw "The option."
                          c counter int "The counter."]
                         identity)
            null-task-no-doc (remove string? null-task)
            expected-null-task (concat (butlast (rest null-task)) '((comp identity)))
            expected-null-task-no-doc (remove string? expected-null-task)]
        (is (= (rest null-task) (update-body (rest null-task) identity)) "It should rebuild the same task form, round-tripping")
        (is (= (rest null-task-no-doc) (update-body (rest null-task-no-doc) identity)) "Even with no docstring it should rebuild the same task form")
        (is (= expected-null-task (update-body (rest null-task) #(cons 'comp (list %)))) "Updating body should work, of course")
        (is (= expected-null-task-no-doc (update-body (rest null-task-no-doc) #(cons 'comp (list %)))) "Updating body should work, of course")))))

(deftesttask vars
  "Testing the test var fetching transformation."
  []
  (core/with-pass-thru fileset
    (testing "namespaces->commands"
      (is (every? var? (namespaces->vars test-me-pred (all-ns))) "The result should be a seq of vars")
      (is (empty? (namespaces->vars test-me-pred [])) "If no namespace in input return the empty sequence"))))
