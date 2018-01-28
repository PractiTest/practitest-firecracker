(defproject practitest-firecracker "0.3.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure                              "1.9.0"]
                 [org.clojure/tools.cli                            "0.3.5"]
                 [cheshire                                         "5.8.0"]
                 [clj-http                                         "3.7.0"]
                 [org.apache.maven.surefire/surefire-report-parser "2.20.1"]]

  :main ^:skip-aot practitest-firecracker.core
  :target-path "target/%s"
  :uberjar-name "practitest-firecracker-standaline.jar"

  :repl-options {:init-ns user
                 :init    (set! *print-length* 100)}

  :profiles {:uberjar {:aot :all}
             :dev     {:source-paths ["dev"]}})
