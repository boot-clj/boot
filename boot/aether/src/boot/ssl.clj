(ns boot.ssl
  (:require
    [boot.util :as util]
    [cemerick.pomegranate.aether :as aether]
    [clojure.java.io :as io])
  (:import
    java.security.KeyStore
    java.security.KeyStore$TrustedCertificateEntry
    java.security.Security
    java.security.cert.CertificateFactory
    javax.net.ssl.KeyManagerFactory
    javax.net.ssl.SSLContext
    javax.net.ssl.TrustManagerFactory
    javax.net.ssl.X509TrustManager
    java.io.FileInputStream
    org.apache.http.config.RegistryBuilder
    org.apache.http.conn.socket.PlainConnectionSocketFactory
    org.apache.http.conn.ssl.BrowserCompatHostnameVerifier
    org.apache.http.conn.ssl.SSLConnectionSocketFactory
    org.apache.http.impl.conn.PoolingHttpClientConnectionManager
    org.apache.maven.wagon.providers.http.HttpWagon))

(defn ^TrustManagerFactory trust-manager-factory [^KeyStore keystore]
  (doto (TrustManagerFactory/getInstance "PKIX")
    (.init keystore)))

(defn ^{:boot/from :technomancy/leiningen} default-trust-managers []
  (let [tmf (trust-manager-factory nil)
        tms (.getTrustManagers tmf)]
    (filter #(instance? X509TrustManager %) tms)))

(defn key-manager-props []
  (let [read #(java.lang.System/getProperty %)]
    (merge {:file (read "javax.net.ssl.keyStore")
            :type (read "javax.net.ssl.keyStoreType")
            :provider (read "javax.net.ssl.keyStoreProvider")
            :password (read "javax.net.ssl.keyStorePassword")}
           ;; TODO: support custom key-manager-properties
           {})))

(defn ^{:boot/from :technomancy/leiningen} key-manager-factory [{:keys [file type provider password]}]
  (let [type (or type (KeyStore/getDefaultType))
        fis (if-not (empty? file) (FileInputStream. file))
        pwd (and password (.toCharArray password))
        store (if provider
                (KeyStore/getInstance type provider)
                (KeyStore/getInstance type))]
    (.load store fis pwd)
    (when fis (.close fis))
    (doto (KeyManagerFactory/getInstance
            (KeyManagerFactory/getDefaultAlgorithm))
      (.init store pwd))))

(defn ^{:boot/from :technomancy/leiningen} default-trusted-certs
  "Lists the CA certificates trusted by the JVM."
  []
  (mapcat #(.getAcceptedIssuers %) (default-trust-managers)))

(defn ^{:boot/from :technomancy/leiningen} read-certs
  "Read one or more X.509 certificates in DER or PEM format."
  [f]
  (try
    (let [cf (CertificateFactory/getInstance "X.509")
          in (io/input-stream (or (io/resource f) (io/file f)))]
      (.generateCertificates cf in))
    (catch Exception _
      (util/warn "Unable to read certificate: %s\n" f))))

(defn ^{:boot/from :technomancy/leiningen} make-keystore
  "Construct a KeyStore that trusts a collection of certificates."
  [certs]
  (let [ks (KeyStore/getInstance "jks")]
    (.load ks nil nil)
    (doseq [[i cert] (map vector (range) certs)]
      (.setEntry ks (str i) (KeyStore$TrustedCertificateEntry. cert) nil))
    ks))

(defn ^{:boot/from :technomancy/leiningen} make-sslcontext
  "Construct an SSLContext that trusts a collection of certificates."
  [trusted-certs]
  (let [ks (make-keystore trusted-certs)
        kmf (key-manager-factory (key-manager-props))
        tmf (trust-manager-factory ks)]
    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

(alter-var-root #'make-sslcontext memoize)

(defn ^{:boot/from :technomancy/leiningen} https-registry
  "Constructs a registry map that uses a given SSLContext for https."
  [context]
  (let [factory (SSLConnectionSocketFactory. context (BrowserCompatHostnameVerifier.))]
    {"https" factory
     "http" PlainConnectionSocketFactory/INSTANCE}))

(defn- ^{:boot/from :technomancy/leiningen} map->registry
  "Creates a Registry based of the given map."
  [m]
  (let [rb (RegistryBuilder/create)]
    (doseq [[scheme conn-sock-factory] m]
      (.register rb scheme conn-sock-factory))
    (.build rb)))

(defn override-wagon-registry!
  "Override the registry scheme used by the HTTP Wagon's Connection
  manager (used for Aether)."
  [registry]
  (let [cm (PoolingHttpClientConnectionManager. (map->registry registry))]
    (HttpWagon/setPoolingHttpClientConnectionManager cm)))
