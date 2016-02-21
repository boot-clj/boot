(ns boot.task.built-in-test
  (:require [clojure.test :refer :all]
            [boot.core :refer :all]
            [boot.task.built-in :refer :all]
            [boot.test :as boot-test]
            [boot.pod :as pod]))

;;;;;;;;;;;;;;;;;;;;;;;;;
;;; sift --with-meta  ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(deftask ^:private with-meta-tests []
  (with-pre-wrap fileset
    (let [tmpfiles (output-files fileset)
          tmpfiles-with-meta (filter :boot-test-tag tmpfiles)
          tmpfiles-clj (by-re [#".clj$"] tmpfiles)]
      (is (= tmpfiles-with-meta tmpfiles-clj) "only .clj files should result from sift :with-meta #{boot-test-tag}"))
    fileset))

(deftask sift-with-meta-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"}) ;; populate
         (sift :add-meta {#".clj$" :boot-test-tag})
         (sift :with-meta #{:boot-test-tag})
         (with-meta-tests))))

(deftask ^:private with-meta-invert-tests []
  (with-pre-wrap fileset
    (let [tmpfiles (output-files fileset)
          tmpfiles-without-meta (remove :boot-test-tag tmpfiles)
          tmpfiles-not-clj (not-by-re [#".clj$"] tmpfiles)]
      (is (= tmpfiles-without-meta tmpfiles-not-clj) "only non .clj files should result from sift :with-meta #{boot-test-tag} invert"))
    fileset))

(deftask sift-with-meta-invert-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"}) ;; populate
         (sift :add-meta {#".clj$" :boot-test-tag})
         (sift :with-meta #{:boot-test-tag} :invert true)
         (with-meta-invert-tests))))

;;;;;;;;;;;;;;;;;;;;;;;
;;; sift --include  ;;;
;;;;;;;;;;;;;;;;;;;;;;;

(deftask ^:private  include-tests []
  (with-pre-wrap fileset
    (let [tmpfiles (output-files fileset)
          tmpfiles-clj-mf (by-re [#".clj$" #".MF$"] tmpfiles)
          tmpfiles-others (not-by-re [#".clj$" #".MF$"] tmpfiles)]
      (is (not (empty? tmpfiles-clj-mf)) ".clj and .MD files should be in output")
      (is (empty? tmpfiles-others) "Output files (no .clj and .MD) should be empty"))
    fileset))

(deftask sift-include-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"}) ;; populate
         (sift :include #{#".clj$" #".MD$"})
         (include-tests))))

(deftask ^:private include-invert-tests []
  (with-pre-wrap fileset
    (let [tmpfiles (output-files fileset)
          tmpfiles-clj-mf (by-re [#".clj$" #".MF$"] tmpfiles)
          tmpfiles-others (not-by-re [#".clj$" #".MF$"] tmpfiles)]
      (is (empty? tmpfiles-clj-mf) ".clj and .MD files should not be in output")
      (is (not (empty? tmpfiles-others)) "Output files (no .clj and .MD) should not be empty"))
    fileset))

(deftask sift-include-invert-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"}) ;; populate
         (sift :include #{#"\.clj$" #"\.MF$"} :invert true)
         (include-invert-tests))))

;;;;;;;;;;;;;;;;;;;;;;;;
;;; sift --to-asset  ;;;
;;;;;;;;;;;;;;;;;;;;;;;;

;; AR - adding this in order to test sift-mv

(deftask ^:private to-asset-tests []
  (with-pre-wrap fileset
    (let [mf-input-files (by-re [#".*\.MF$"] (input-files fileset))
          mf-output-files (by-re [#".*\.MF$"] (output-files fileset))]
      (is (empty? mf-input-files) "The .MF files should not have input role")
      (is (not (empty? mf-output-files)) "The .MF files should have output role"))
    fileset))

(deftask sift-to-asset-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"} ;; populate
               :to-asset #{#".*\.MF$"})
         (to-asset-tests))))

(deftask ^:private to-asset-invert-tests []
  (with-pre-wrap fileset
    (let [non-mf-input-files (not-by-re [#".*\.MF$"] (input-files fileset))
          non-mf-output-files (not-by-re [#".*\.MF$"] (output-files fileset))]
      (is (empty? non-mf-input-files) "The .MF files should not have input role")
      (is (not (empty? non-mf-output-files)) "The .MF files should have output role"))
    fileset))

(deftask sift-to-asset-invert-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"} ;; populate
               :to-asset #{#".*\.MF$"}
               :invert true)
         (to-asset-invert-tests))))

;;;;;;;;;;;;;;;;;;;;;;;;
;;; sift --add-meta  ;;;
;;;;;;;;;;;;;;;;;;;;;;;;

(deftask ^:private add-meta-tests []
  (with-pre-wrap fileset
    (let [tmpfiles (output-files fileset)
          tmpfiles-with-meta (filter :boot-test-tag tmpfiles)]
      (is (empty? (not-by-re [#".clj$"] tmpfiles-with-meta)) "non .clj files should not have :boot-test-tag metadata")
      (is (seq (by-re [#".clj$"] tmpfiles-with-meta)) "only .clj files should have :boot-test-tag metadata"))
    fileset))

(deftask sift-add-meta-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"}) ;; populate
         (sift :add-meta {#".clj$" :boot-test-tag})
         (add-meta-tests))))

(deftask ^:private add-meta-invert-tests []
  (with-pre-wrap fileset
    (let [tmpfiles (output-files fileset)
          tmpfiles-with-meta (filter :boot-test-tag tmpfiles)]
      (is (empty? (by-re [#".clj$"] tmpfiles-with-meta)) ".clj files should not have :boot-test-tag metadata")
      (is (seq (not-by-re [#".clj$"] tmpfiles-with-meta)) "only non .clj files should have :boot-test-tag metadata"))
    fileset))

(deftask sift-add-meta-invert-tests []
  (boot-test/test-task
   (comp (sift :add-jar {'org.clojure/tools.reader #".*"}) ;; populate
         (sift :add-meta {#".clj$" :boot-test-tag} :invert true)
         (add-meta-invert-tests))))
