(ns boot.pom-test
  (:require [boot.pom :as pom]
            [clojure.test :as test :refer [deftest is testing]]
            [clojure.zip :as zip]
            [clojure.data.xml :as dxml]
            [clojure.data.zip.xml :as dzxml]))

(deftest pom-parent

  (testing "pom-xml-parse-string"
    (let [xml-str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n  xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n                      https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n  <modelVersion>4.0.0</modelVersion>\n \n  <parent>\n    <groupId>org.codehaus.mojo</groupId>\n    <artifactId>my-parent</artifactId>\n    <version>2.0</version>\n    <relativePath>../my-parent</relativePath>\n  </parent>\n \n  <artifactId>my-project</artifactId>\n</project>\n"
          {:keys [parent]} (pom/pom-xml-parse-string xml-str)]
      (is (= '[org.codehaus.mojo/my-parent "2.0"] (:dependency parent)) "The parent :dependency must exist and match the example")
      (is (= "../my-parent" (:relative-path parent)) "The parent :relative-path key must exist and match the example"))

    (let [xml-str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n  xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n                      https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n  <modelVersion>4.0.0</modelVersion>\n \n  <groupId>org.codehaus.mojo</groupId>\n  <artifactId>my-parent</artifactId>\n  <version>2.0</version>\n  <packaging>pom</packaging>\n</project>"
          {:keys [parent]} (pom/pom-xml-parse-string xml-str)]
      (is (nil? parent) "The parent tag must not exist if missing in the pom"))

    (let [xml-str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n  xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n                      https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n  <modelVersion>4.0.0</modelVersion>\n \n  <parent>\n    <artifactId>my-parent</artifactId>\n    <version>2.0</version>\n  </parent>\n \n  <artifactId>my-project</artifactId>\n</project>\n"
          {:keys [parent]} (pom/pom-xml-parse-string xml-str)]
      (is (= '[my-parent "2.0"] (-> :dependency parent)) "The parent :dependency must exist and be just the artifactId of the example")
      (is (nil? (-> parent :dependency first namespace)) "The parent :dependency must exist but should not have the groupId as per example")
      (is (nil? (:relative-path parent)) "The parent :relative-path must be nil as it is not in the example"))

    ;; Edge case - parent tag is there but does not have artifactId
    (let [xml-str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n  xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n                      https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n  <modelVersion>4.0.0</modelVersion>\n \n  <parent>\n    <groupId>org.codehaus.mojo</groupId>\n    </parent>\n \n  <artifactId>my-project</artifactId>\n</project>\n"
          {:keys [parent]} (pom/pom-xml-parse-string xml-str)]
      (is (nil? (-> :dependency parent)) "The parent :dependency should be nil if no artifactId is there")))

  (testing "pom-xml :parent"
    (let [parent-loc (-> {:project 'group/my-plugin
                          :version "1.2.0"
                          :parent '[org.codehaus.mojo/my-parent "2.0"]}
                         pom/pom-xml
                         pr-str
                         dxml/parse-str
                         zip/xml-zip
                         (dzxml/xml1-> :parent))]
      (is (not (nil? (dzxml/xml1-> parent-loc :groupId "org.codehaus.mojo"))) "I should contain a :groupId tag with correct content")
      (is (not (nil? (dzxml/xml1-> parent-loc :artifactId "my-parent"))) "It should contain a :artifactId tag with correct content")
      (is (not (nil? (dzxml/xml1-> parent-loc :version "2.0"))) "It should contain a :version tag with correct content"))))
