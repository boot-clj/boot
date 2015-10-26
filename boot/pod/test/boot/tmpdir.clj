(ns boot.tmpdir-test
  (:refer-clojure :exclude [sync file-seq])
  (:require
    [clojure.test :refer :all]
    [boot.file :as file]
    [boot.tmpdir :as fs]
    [clojure.java.io :as io]))

(def systmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "boot-fileset-test-" (gensym))))

(defn cleanup! [& dirs]
  (apply file/empty-dir! dirs))

 (use-fixtures :each (fn [f] (f) #_(cleanup! systmp)))

(defn get-tmp-dir! [name]
  (doto (io/file systmp name)
    (io/make-parents)
    (.mkdir)))

(defn create-random! []
  (let [dir (get-tmp-dir! (str (gensym)))
        r1 (str (gensym)) r2 (str (gensym))]
    (doto (io/file dir r1 r2 "new-file.txt")
      (io/make-parents)
      (spit "file-contents"))
    [dir (io/file r1 r2 "new-file.txt")]))

(deftest fileset-commit!
  (testing "writes files not in dirs"
    (let [blob  (fs/map->TmpDir {:dir (get-tmp-dir! "blob")})
          dir   (fs/map->TmpDir {:dir (get-tmp-dir! "dirs")})
          fs*   (boot.tmpdir.TmpFileSet. [dir] {} #{} (fs/dir blob))
          [d f] (create-random!)]
      (fs/commit! (fs/add fs* (fs/dir dir) d {}))
      (is (= (slurp (io/file (fs/dir dir) f))
             "file-contents"))))
  (testing "removes stale files"
    (let [blob  (fs/map->TmpDir {:dir (get-tmp-dir! "blob")})
          dir   (fs/map->TmpDir {:dir (get-tmp-dir! "dirs")})
          fs*   (boot.tmpdir.TmpFileSet. [dir] {} #{} (fs/dir blob))
          [d f] (create-random!)]
      (fs/commit! (fs/add fs* (fs/dir dir) d {}))
      (is (.exists (io/file (fs/dir dir) f)))
      (fs/commit! fs*)
      (is (not (.exists (io/file (fs/dir dir) f))))))
  (testing "..."
    (let [blob  (fs/map->TmpDir {:dir (get-tmp-dir! "blob")})
          dir   (fs/map->TmpDir {:dir (get-tmp-dir! "dirs")})
          fs*   (boot.tmpdir.TmpFileSet. [dir] {} #{} (fs/dir blob))
          [d1 foo] (create-random!)
          [d2 bar] (create-random!)]
      (let [with-foo (fs/commit! (fs/add fs* (fs/dir dir) d1 {}))]
        (is (.exists (io/file (fs/dir dir) foo)))
        (let [with-foobar (fs/commit! (fs/add with-foo (fs/dir dir) d2 {}))]
          (is (.exists (io/file (fs/dir dir) bar)))
          (is (.exists (io/file (fs/dir dir) foo)))
          (fs/commit! fs*)
          (fs/commit! with-foo)
          (is (not (.exists (io/file (fs/dir dir) bar))))
          (is (.exists (io/file (fs/dir dir) foo))))))))
