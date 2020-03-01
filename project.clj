(defproject practitest-firecracker "0.4.2"
  :description "CLI to parse and upload surefire reports to PractiTest as TestSet"
  :url "https://github.com/PractiTest/practitest-firecracker"

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :plugins [[reifyhealth/lein-git-down "0.3.5"]]
  :middleware [lein-git-down.plugin/inject-properties]

  :dependencies [[org.clojure/clojure                              "1.10.0"]
                 [org.clojure/tools.cli                            "0.4.2"]
                 [cheshire                                         "5.8.1"]
                 [clj-http                                         "3.10.0"]
                 [org.apache.maven.surefire/surefire-report-parser "2.22.2"]
                 [org.clojure/tools.logging                        "0.4.1"]
                 [ch.qos.logback/logback-classic                   "1.2.3"]
                 [test-xml-parser                                  "d0e429876bc4452fdac0392afc671741f81a4e09"]
                 ]

  :repositories [["private-github" {:url "git@github.com:PractiTest/test-xml-parser.git" :protocol :ssh}]]
  :git-down {test-xml-parser {:coordinates PractiTest/test-xml-parser}}
  ;; :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]
  ;; :git-dependencies [["git@github.com:PractiTest/test-xml-parser.git" "e856a09cecfd7210926a3f5ae9f3802bea985bc4" "deps"]]
  ;; :source-paths ["src" "test" ".lein-git-deps/src/test_xml_parser/"]

  :main ^:skip-aot practitest-firecracker.core
  :target-path "target/%s"
  :uberjar-name "practitest-firecracker-standalone.jar"
  :repl-options {:init-ns user
                 :init    (set! *print-length* 100)}

  :profiles {:uberjar {:aot :all}
             :dev     {:source-paths ["dev"]}})
