(ns tailrecursion.boot-test
  (:require 
    [clojure.pprint :refer [pprint]]
    [clojure.test :refer :all]
    [tailrecursion.boot :refer :all]))

(defn main [boot]
  (pprint boot)
  (fn [continue]
    (fn [event]
      (continue event))))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
