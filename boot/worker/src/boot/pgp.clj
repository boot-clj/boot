(ns boot.pgp
  (:require
   [clojure.java.io   :as io]
   [boot.pod          :as pod]
   (clj-pgp
     [core :as pgp]
     [keyring :as keyring]
     [signature :as pgp-sig]))
  (:import
   [java.util.regex Pattern]
   [java.io StringBufferInputStream]))

(defn find-secring []
  (let [home (System/getenv "HOME")
        locations [(System/getenv "GNUPGHOME") (System/getenv "BOOT_GPG_SECRING") (str home "/.gnupg/secring.gpg") (str home "/.gpg/secring.gpg")]
        exists-fn #(when (and % (.exists (io/as-file %))) %)
        secring (some exists-fn locations)]
    (or secring (throw (Exception. "Can't find secring file. Please set 'BOOT_GPG_SECRING'")))))

(defn get-keyring []
  (keyring/load-secret-keyring (io/file (find-secring))))

(defn find-master-key-by-user [user]
  (let [keyring (get-keyring)
        pub-keys (keyring/list-public-keys keyring)
        re-user #(re-find (re-pattern user) %)
        match?  #(some re-user (-> % pgp/key-info :user-ids))
        key (some #(when (match? %) %) pub-keys)]
    (or key (throw (Exception. (format "User %s not found" user))))))

(defn find-master-key []
  (if-let [user (System/getenv "BOOT_GPG_USER")]
    (find-master-key-by-user user)
    (let [keyring (get-keyring)
          master-keys (filter (comp :master-key? pgp/key-info) (keyring/list-public-keys keyring))]
      (cond
        (= 0 (count master-keys)) (throw (Exception. "No master key found"))
        (= 1 (count master-keys)) (first master-keys)
        :else (throw (Exception. "Multiple master keys found. Please set 'BOOT_GPG_USER'"))))))

(defn get-private-key-by-id [hex-id passphrase]
  (let [keyring (get-keyring)
        seckey (keyring/get-secret-key keyring hex-id)]
    (pgp/unlock-key seckey passphrase)))

(defn get-private-key [passphrase]
  (let [keyring (get-keyring)]
    (if-let [hex-id (System/getenv "BOOT_GPG_SIGNING_KEY_ID")]
      (get-private-key-by-id hex-id passphrase)
      (-> (find-master-key)
          pgp/key-info
          :key-id
          (#(keyring/get-secret-key keyring %))
          (pgp/unlock-key passphrase)))))

(defn read-pass
  [prompt]
  (String/valueOf (.readPassword (System/console) prompt nil)))

(defn sign [content]
  (let [passphrase (read-pass (str "\nGPG passphrase: "))
        pr (get-private-key passphrase)]
    (-> content (pgp-sig/sign pr) pgp/encode-ascii)))

(defn sign-jar
  [outpath jarpath]
  (let [outdir  (doto (io/file outpath) .mkdirs)
        jarname (.getName (io/file jarpath))
        pomxml  (StringBufferInputStream. (pod/pom-xml jarpath))
        jarout  (io/file outdir (str jarname ".asc"))
        pomout  (io/file outdir (.replaceAll jarname "\\.jar$" ".pom.asc"))
        sign-it #(sign %)]
    (spit pomout (sign-it pomxml))
    (spit jarout (sign-it (io/input-stream jarpath)))
    {[:extension "jar.asc"] (.getPath jarout)
     [:extension "pom.asc"] (.getPath pomout)}))
