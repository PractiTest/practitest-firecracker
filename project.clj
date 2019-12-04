(defproject practitest-firecracker "0.4.2"
  :description "CLI to parse and upload surefire reports to PractiTest as TestSet"
  :url "https://github.com/PractiTest/practitest-firecracker"

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure                              "1.10.0"]
                 [org.clojure/tools.cli                            "0.4.2"]
                 [cheshire                                         "5.8.1"]
                 [clj-http                                         "3.10.0"]
                 [org.apache.maven.surefire/surefire-report-parser "2.22.2"]
                 [org.clojure/tools.logging                        "0.4.1"]
                 [ch.qos.logback/logback-classic                   "1.2.3"]]

  :main ^:skip-aot practitest-firecracker.core
  :target-path "target/%s"
  :uberjar-name "practitest-firecracker-standalone.jar"
  :repl-options {:init-ns user
                 :init    (set! *print-length* 100)}

  :profiles {:uberjar {:aot :all}
             :dev     {:source-paths ["dev"]}})
