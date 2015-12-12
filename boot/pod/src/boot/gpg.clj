(ns boot.gpg
  (:require
   [clojure.java.io   :as io]
   [clojure.java.shell :as shell]
   [boot.pod          :as pod]
   [boot.util         :as util])
  (:import [java.io StringReader File]))

(defn ^{:boot/from :technomancy/leiningen} gpg-program
  "Lookup the gpg program to use, defaulting to 'gpg'"
  []
  (or (boot.App/config "BOOT_GPG_COMMAND") "gpg"))

(defn- ^{:boot/from :technomancy/leiningen} get-english-env []
  "Returns environment variables as a map with clojure keywords and LANGUAGE set to 'en'"
  (let [env (System/getenv)
        keywords (map #(keyword %) (keys env))]
    (merge (zipmap keywords (vals env))
           {:LANGUAGE "en"})))

(defn ^{:boot/from :technomancy/leiningen} gpg
  "Shells out to (gpg-program) with the given arguments"
  [& args]
  (let [env (get-english-env)]
    (try
      (shell/with-sh-env env
        (apply shell/sh (gpg-program) args))
      (catch java.io.IOException e
        {:exit 1 :err (.getMessage e)}))))

(defn ^{:boot/from :technomancy/leiningen} signing-args
  "Produce GPG arguments for signing a file."
  [file opts]
  (let [key-spec (if-let [key (:gpg-key opts)]
                   ["--default-key" key])
        passphrase-spec (if-let [pass (:gpg-passphrase opts)]
                          ["--passphrase-fd" "0"])
        passphrase-in-spec (if-let [pass (:gpg-passphrase opts)]
                             [:in (StringReader. pass)])]
    `["--yes" "-ab" ~@key-spec ~@passphrase-spec "--" ~file ~@passphrase-in-spec]))

(defn ^{:boot/from :technomancy/leiningen} sign
  "Create a detached signature and return the signature file name."
  [file opts]
  (let [{:keys [err exit]} (apply gpg (signing-args file opts))]
    (when-not (zero? exit)
      (util/fail (str "Could not sign " file "\n" err
                      "\n\nIf you don't expect people to need to verify the "
                      "authorship of your jar, don't set :gpg-sign option of push task to true.\n")))
    (str file ".asc")))

(defn sign-jar
  [outdir jarfile pompath opts]
  (shell/with-sh-dir
    outdir
    (let [jarname (.getName jarfile)
          jarout  (io/file outdir (str jarname ".asc"))
          pomfile (doto (File/createTempFile "pom" ".xml")
                    (.deleteOnExit)
                    (spit (pod/pom-xml jarfile pompath)))
          pomout  (io/file outdir (.replaceAll jarname "\\.jar$" ".pom.asc"))
          sign-it #(slurp (sign (.getPath %) opts))]
      (spit pomout (sign-it pomfile))
      (spit jarout (sign-it jarfile))
      {[:extension "jar.asc"] (.getPath jarout)
       [:extension "pom.asc"] (.getPath pomout)})))

(defn decrypt
  "Use gpg to decrypt a file -- returns string contents of file."
  [file]
  (let [path (.getPath (io/file file))
        {:keys [out err exit]} (gpg "--quiet" "--batch" "--decrypt" "--" path)]
    (assert (zero? exit) err)
    out))
