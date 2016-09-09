(ns boot.util-test
  (:require
   [clojure.test :refer :all]
   [boot.util :as util :refer :all]))

(deftest check-canonical-id
  (is (nil? (canonical-id nil)))
  (is (nil? (canonical-id "BAD")))
  (is (nil? (canonical-id 0xBAD)))
  (is (= 'foo (canonical-id 'foo/foo)))
  (is (= 'foo/bar (canonical-id 'foo/bar))))

(deftest check-full-id
  (is (nil? (full-id nil)))
  (is (nil? (full-id "BAD")))
  (is (nil? (full-id 0xBAD)))
  (is (= 'foo/foo (full-id 'foo)))
  (is (= 'foo/bar (full-id 'foo/bar))))

(deftest check-canonical-coord
  (is (nil? (canonical-coord nil)))
  (is (nil? (canonical-coord [])))
  (is (thrown? Exception (canonical-coord 0xBAD)))
  (is (= '[foo "1.2.3" :scope "test"] (canonical-coord '[foo/foo "1.2.3" :scope "test"])))
  (is (= '[foo/bar "1.2.3" :scope "test"] (canonical-coord '[foo/bar "1.2.3" :scope "test"]))))

(deftest dep-mgt-functions
  
  (let [project 'com.example/project
        version "1.2.3"
        scope "test"
        exclusions [['com.example/excl1 :extension "jar"]
                    'com.example/excl2]]
    
    (testing "simple dep-as-map conversions"
      (are [input expected] (= expected (dep-as-map input))

           nil
           {:project nil
            :version nil
            :scope "compile"}

           []
           {:project nil
            :version nil
            :scope "compile"}
           
           [project]
           {:project project
            :version nil
            :scope "compile"}
           
           [project version]
           {:project project
            :version version
            :scope "compile"}
           
           [project version :scope scope]
           {:project project
            :version version
            :scope scope}
           
           [project version :scope scope :exclusions exclusions]
           {:project project
            :version version
            :scope scope
            :exclusions exclusions}

           ;; checks that options with nil values are retained
           [project version :scope scope :exclusions exclusions :other nil]
           {:project project
            :version version
            :scope scope
            :exclusions exclusions
            :other nil}

           ;; checks that optional version with option works
           [project :scope scope]
           {:project project
            :version nil
            :scope scope}))
    
    (testing "simple map-as-dep conversions"      
      (are [input expected] (= expected (map-as-dep input))

           {}
           []
           
           {:project project
            :version nil
            :scope "compile"}
           [project]
           
           {:project project
            :version version
            :scope "compile"}
           [project version]
           
           {:project project
            :version version
            :scope scope}
           [project version :scope scope]
           
           {:project project
            :version version
            :exclusions exclusions}
           [project version :exclusions exclusions]

           ;; checks that options with nil values are retained
           {:project project
            :version version
            :other nil}
           [project version :other nil]

           ;; checks that optional version with option works
           {:project project
            :version nil
            :scope scope}
           [project :scope scope]))

    (testing "roundtripping deps"
      
      (are [input] (= input (dep-as-map (map-as-dep input)))
           
           {:project project
            :version nil
            :scope "compile"}
           
           {:project project
            :version version
            :scope "compile"}
           
           {:project project
            :version version
            :scope scope}
           
           {:project project
            :version version
            :scope scope
            :exclusions exclusions}

           ;; checks that options with nil values are retained
           {:project project
            :version version
            :scope scope
            :exclusions exclusions
            :other nil}

           ;; checks that optional version with option works
           {:project project
            :version nil
            :scope scope}))

    (testing "check unusual arguments"
      (is (thrown? Exception (dep-as-map {})))
      (is (= [] (map-as-dep nil)))
      (is (= [] (map-as-dep [])))
      (is (thrown? Exception (map-as-dep 3))))))
