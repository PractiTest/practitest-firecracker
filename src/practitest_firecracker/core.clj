(ns practitest-firecracker.core
  (:require
   [practitest-firecracker.cli         :refer [parse-args]]
   [practitest-firecracker.practitest  :refer [make-client
                                               make-runs
                                               create-testsets
                                               group-tests
                                               create-or-update-tests
                                               create-instances
                                               create-runs
                                               fc-version]]
   [practitest-firecracker.parser.core :refer [send-directory parse-files]]
   [practitest-firecracker.utils       :refer [exit]]
   [clojure.pprint                     :as     pprint]
   [clojure.java.io                    :as     io]
   [clj-time.core                      :as     t])
  (:gen-class))

(defmacro timef
  [module expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (binding [*out* *err*]
       (println (str ~module " elapsed time: " (/ (double (- (. System (nanoTime)) start#)) 1000000.0) " msecs")))
     ret#))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (parse-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [client             (make-client (select-keys options [:email :api-token :api-uri :max-api-rate]))
            directory          (:reports-path options)
            dirs               (when-not (nil? directory) (for [dir directory] (io/file dir)))
            parsed-dirs        (when-not (nil? dirs) (for [dir (file-seq (first dirs))] (parse-files dir)))
            additional-reports (send-directory parsed-dirs (:test-case-as-pt-test-step options) (:multitestset options) (:testset-name options) false)
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
            (println "Version: " fc-version)

            "create-and-populate-testset"
            (do
              (timef
               "create-and-populate-testset"
               (-> (create-testsets client options additional-reports)
                   (group-tests client options)
                   (create-or-update-tests client options start-time)
                   (create-instances client options start-time)
                   (make-runs client options start-time)
                   (create-runs client options start-time)
                   ))
              (exit 0 (format "Done"))))))))
