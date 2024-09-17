(ns practitest-firecracker.core
  (:require
   [practitest-firecracker.cli         :refer [parse-args]]
   [practitest-firecracker.practitest  :refer [make-runs
                                               create-testsets
                                               group-tests
                                               create-or-update-tests
                                               create-instances
                                               create-runs]]
   [practitest-firecracker.parser.core :refer [send-directory parse-files]]
   [practitest-firecracker.parser.gherkin :as gherkin]
   [practitest-firecracker.api         :refer [make-client]]
   [practitest-firecracker.utils       :refer [exit]]
   [clojure.pprint                     :as     pprint]
   [clojure.java.io                    :as     io]
   [clj-time.core                      :as     t]
   [clojure.tools.logging              :as log]
   [practitest-firecracker.const       :refer [fc-version]])
  (:import (org.slf4j LoggerFactory)
           (ch.qos.logback.classic Level Logger))
  (:gen-class))

(defmacro timef
  [module expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (binding [*out* *err*]
       (println (str ~module " elapsed time: " (/ (double (- (. System (nanoTime)) start#)) 1000000.0) " msecs")))
     ret#))

(def ^:private logback-levels
  {:trace "TRACE"
   :debug "DEBUG"
   :info  "INFO"
   :warn  "WARN"
   :error "ERROR"})

(defn- set-log-level! [ns level]
  (let [lvl (Level/toLevel ^String (get logback-levels level))
        ^Logger logger (LoggerFactory/getLogger (str ns))
        old (.getLevel logger)]
    (.setLevel logger lvl)
    old))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (parse-args args)]
    ;; Enabled debug logging level
    (when (:debug options)
      (set-log-level! "ROOT" :debug)
      (log/debug "Debug output enabled"))

    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [client             (make-client (select-keys options [:email :api-token :api-uri :max-api-rate]))
            directory          (:reports-path options)
            dirs               (when-not (nil? directory) (for [dir directory] (io/file dir)))
            parsed-dirs        (when-not (nil? dirs) (for [dir (file-seq (first dirs))] (parse-files dir)))
            scenarios-map      (when-let [features-path (:features-path options)]
                                 (gherkin/parse-all-features features-path))
            options            (assoc options
                                 :scenarios-map scenarios-map
                                 :sample false)
            additional-reports (send-directory parsed-dirs options)
            start-time         (t/now)]
          (case action
            "display-config"
            (do
              (pprint/pprint {"=============== additional-reports: ===============" additional-reports})
              (pprint/pprint {"=============== directory: ===============" directory}))

            "display-options"
            (do
              (pprint/pprint {"=============== options: ===============" options})
              (pprint/pprint {"=============== args: ===============" args}))

            "version"
            (log/info "Version: " fc-version)

            "create-and-populate-testset"
            (do
              (log/info "Start Running Firecracker, Version: " fc-version)
              (timef
               "create-and-populate-testset"
               (-> (create-testsets client options additional-reports)
                   (group-tests client options)
                   (create-or-update-tests client options start-time)
                   (create-instances client options start-time)
                   (make-runs client options start-time)
                   (create-runs client options start-time)))
              (exit 0 (format "Done"))))))))

;; Manual run
(comment
  (let [
        {:keys [action options exit-message ok?]}
        (parse-args ["--config-path" "/Users/deshilov/work/practitest/firecracker-env/FC_localhost_BDD.json"
                     "--reports-path" "/Users/deshilov/work/practitest/bdd/test2"
                     "--features-path" "/Users/deshilov/work/practitest/bdd/test1"
                     "--api-uri" "http://localhost:3000"
                     "--display-action-logs"
                     "--author-id" "2"
                     "--project-id" "3"
                     "--detect-bdd-steps"
                     "--display-action-logs"
                     "create-and-populate-testset"])

        client             (make-client (select-keys options [:email :api-token :api-uri :max-api-rate]))
        directory          (:reports-path options)
        dirs               (when-not (nil? directory) (for [dir directory] (io/file dir)))
        parsed-dirs        (when-not (nil? dirs) (for [dir (file-seq (first dirs))] (parse-files dir)))
        scenarios-map      (when-let [features-path (:features-path options)]
                             (gherkin/parse-all-features features-path))
        options            (assoc options
                             :scenarios-map scenarios-map
                             :sample false)
        additional-reports (send-directory parsed-dirs options)
        start-time         (t/now)


        ]

    (-> (create-testsets client options additional-reports)
        (group-tests client options)
        (create-or-update-tests client options start-time)
        (create-instances client options start-time)
        (make-runs client options start-time)
        (create-runs client options start-time))))
