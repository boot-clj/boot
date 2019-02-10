(ns boot.gpg
  (:require
   [clojure.java.io   :as io]
   [clojure.java.shell :as shell]
   [boot.pod          :as pod]
   [boot.util         :as util])
   [bootstrap.config  :as conf]
  (:import [java.io StringReader File]))

(defn ^{:boot/from :technomancy/leiningen} gpg-program
  "Lookup the gpg program to use, defaulting to 'gpg'"
  []
  (or (:boot-gpg-command (conf/config)) "gpg"))

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

(defn sign-it
  "Sign a java.io.File given the options."
  [f gpg-options]
  (slurp (sign (.getPath f) gpg-options)))

(defn sign-pom
  "Materialize and sign the pom contained in jarfile.
  Returns an artifact-map entry - a map from partial coordinates to file
  path or File (see pomegranate/aether.clj for details).

  If you receive a \"Could not sign ... gpg: no default secret key: secret key
  not available\" error, make sure boot is using the right gpg executable.  You
  can use the BOOT_GPG_COMMAND environment variable for that.

  In order to use gpg2, for instance, run:

    BOOT_GPG_COMMAND=gpg2 boot push --gpg-sign ...

  You rarely need to use this directly, use the push task instead."
  [outdir jarfile pompath gpg-options]
  (shell/with-sh-dir
    outdir
    (let [jarname (.getName jarfile)
          pomfile (doto (File/createTempFile "pom" ".xml")
                    (.deleteOnExit)
                    (spit (pod/pom-xml jarfile pompath)))
          pomout  (io/file outdir (.replaceAll jarname "\\.jar$" ".pom.asc")) ]
      (spit pomout (sign-it pomfile gpg-options))
      [[:extension "pom.asc"] (.getPath pomout)])))

(defn sign-jar
  "Sign a jar.

  Returns an artifact-map entry - a map from partial coordinates to file
  path or File (see pomegranate/aether.clj for details).

  If you receive a \"Could not sign ... gpg: no default secret key: secret key
  not available\" error, make sure boot is using the right gpg executable.  You
  can use the BOOT_GPG_COMMAND environment variable for that.

  In order to use gpg2, for instance, run:

    BOOT_GPG_COMMAND=gpg2 boot push --gpg-sign ...

  You rarely need to use this directly, use the push task instead."
  [outdir jarfile gpg-options]
  (shell/with-sh-dir
    outdir
    (let [jarname (.getName jarfile)
          jarout  (io/file outdir (str jarname ".asc"))]
      (spit jarout (sign-it jarfile gpg-options))
      [[:extension "jar.asc"] (.getPath jarout)])))

(defn decrypt
  "Use gpg to decrypt a file -- returns string contents of file."
  [file]
  (let [path (.getPath (io/file file))
        {:keys [out err exit]} (gpg "--quiet" "--batch" "--decrypt" "--" path)]
    (assert (zero? exit) err)
    out))
