(ns boot.tmpdir-test
  (:refer-clojure :exclude [hash time])
  (:require
    [clojure.test :refer :all]
    [boot.tmpdir :as tmpd]))

(defn has-path? [fs path]
  (contains? (:tree fs) path))

(deftest diff*-scalar-test
  (let [before (tmpd/map->TmpFileSet {:tree {}})
        after (tmpd/map->TmpFileSet {:tree {"path" {:adding true}}})
        diff (#'tmpd/diff* before after [:adding])]
    (testing "Added"
      (is (has-path? (:added diff) "path"))
      (is (not (has-path? (:removed diff) "path")))
      (is (not (has-path? (:changed diff) "path")))))
  (let [before (tmpd/map->TmpFileSet {:tree {"path" {:removing true}}})
        after (tmpd/map->TmpFileSet {:tree {}})
        diff (#'tmpd/diff* before after [:removing])]
    (testing "Removed"
      (is (has-path? (:removed diff) "path"))
      (is (not (has-path? (:added diff) "path")))
      (is (not (has-path? (:changed diff) "path")))))
  (let [before (tmpd/map->TmpFileSet {:tree {"path" {:changing true}}})
        after (tmpd/map->TmpFileSet {:tree {"path" {:changing false}}})
        diff (#'tmpd/diff* before after [:changing])]
    (testing "Changed"
      (is (has-path? (:changed diff) "path"))
      (is (not (has-path? (:added diff) "path")))
      (is (not (has-path? (:removed diff) "path"))))))

(deftest diff*-nested-test
  (let [before (tmpd/map->TmpFileSet {:tree {}})
        after (tmpd/map->TmpFileSet {:tree {"path" {:nested {:adding true}}}})
        diff (#'tmpd/diff* before after [:nested])]
    (testing "Added"
      (is (has-path? (:added diff) "path"))
      (is (not (has-path? (:removed diff) "path")))
      (is (not (has-path? (:changed diff) "path")))))
  (let [before (tmpd/map->TmpFileSet {:tree {"path" {:nested {:removing true}}}})
        after (tmpd/map->TmpFileSet {:tree {}})
        diff (#'tmpd/diff* before after [:nested])]
    (testing "Removed"
      (is (has-path? (:removed diff) "path"))
      (is (not (has-path? (:added diff) "path")))
      (is (not (has-path? (:changed diff) "path")))))
  (let [before (tmpd/map->TmpFileSet {:tree {"path" {:nested {:changing true}}}})
        after (tmpd/map->TmpFileSet {:tree {"path" {:nested {:changing false}}}})
        diff (#'tmpd/diff* before after [:nested])]
    (testing "Changed (simple)"
      (is (has-path? (:changed diff) "path"))
      (is (not (has-path? (:added diff) "path")))
      (is (not (has-path? (:removed diff) "path")))))
  (let [before (tmpd/map->TmpFileSet {:tree {"path" {:nested {:staying true :changing true}}}})
        after (tmpd/map->TmpFileSet {:tree {"path" {:nested {:staying true :changing false}}}})
        diff (#'tmpd/diff* before after [:nested])]
    (testing "Changed (complex)"
      (is (has-path? (:changed diff) "path"))
      (is (not (has-path? (:added diff) "path")))
      (is (not (has-path? (:removed diff) "path"))))))

(deftest diff*-edge-test
  (let [before {}
        after {}
        diff (#'tmpd/diff* before after [:hash :time])
        empty-fs {:tree {}}]
    (testing "Empty dicts"
      (is (= empty-fs (:added diff)))
      (is (= empty-fs (:removed diff)))
      (is (= empty-fs (:changed diff)))))
  (let [before nil
        after nil
        diff (#'tmpd/diff* before after [:hash :time])
        empty-fs {:tree {}}]
    (testing "Nil"
      (is (= empty-fs (:added diff)))
      (is (= empty-fs (:removed diff)))
      (is (= empty-fs (:changed diff))))))
