(ns boot.pgp
  (:require
   [clojure.java.io   :as io]
   [boot.pod          :as pod]
   [mvxcvi.crypto.pgp :as pgp])
  (:import
   [java.util.regex Pattern]
   [java.io StringBufferInputStream]))

(def default-secring
  (-> (System/getProperty "user.home")
    (io/file ".gnupg" "secring.gpg")))

(defn secring
  [& [file]]
  (pgp/load-secret-keyring (io/file (or file default-secring))))

(defn get-pubkey
  [secring & [user]]
  (let [ks      (pgp/list-public-keys secring)
        re-user #(re-find (re-pattern (Pattern/quote (str user))) %)
        match?  #(some re-user (-> % pgp/key-info :user-ids))]
    (let [ks' (if-not user ks (filter match? ks))]
      (if (or (not user) (<= (count ks') 1))
        (first ks')
        (throw (ex-info "multiple keys match"
                 {:keys (mapv pgp/key-info ks')}))))))

(defn secret-key-for
  [secring pubkey]
  (pgp/get-secret-key secring (pgp/key-id pubkey)))

(defn prompt-for
  [keyring user-id]
  (if-let [pk (-> keyring secring (get-pubkey user-id))]
    (-> pk pgp/key-info :user-ids first (str "\nGPG passphrase: "))
    (throw (Exception. (format "public key not found (%s)" user-id)))))

(defn sign
  [content passphrase & {:keys [keyring user-id]}]
  (let [sr (secring keyring)
        pk (get-pubkey sr user-id)
        sk (secret-key-for sr pk)
        pr (pgp/unlock-key sk passphrase)]
    (-> content (pgp/sign :sha1 pr) pgp/encode-ascii)))

(defn sign-jar
  [outpath jarpath passphrase & {:keys [keyring user-id]}]
  (let [outdir  (doto (io/file outpath) .mkdirs)
        jarname (.getName (io/file jarpath))
        pomxml  (StringBufferInputStream. (pod/pom-xml jarpath))
        jarout  (io/file outdir (str jarname ".asc"))
        pomout  (io/file outdir (.replaceAll jarname "\\.jar$" ".pom.asc"))
        sign-it #(sign % passphrase :keyring keyring :user-id user-id)]
    (spit pomout (sign-it pomxml))
    (spit jarout (sign-it (io/input-stream jarpath)))
    {[:extension "jar.asc"] (.getPath jarout)
     [:extension "pom.asc"] (.getPath pomout)}))
