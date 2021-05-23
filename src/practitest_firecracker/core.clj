(ns practitest-firecracker.core
  (:require
   [practitest-firecracker.cli        :refer [parse-args]]
   [practitest-firecracker.practitest :refer [make-client
                                              create-sf-testset
                                              populate-sf-results-old
                                              populate-sf-results
                                              create-or-update-sf-testset]]
   [firecracker-report-parser.core    :refer [send-directory parse-files]]
   [clojure.pprint                    :as     pprint]
   [clojure.java.io                   :refer [file]])
  (:gen-class))

(defn exit [status msg]
  (println msg)
  (System/exit status))

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
            dirs               (for [dir directory] (clojure.java.io/file dir))
            parsed-dirs        (for [dir (file-seq (first dirs))] (parse-files dir))
            additional-reports (send-directory parsed-dirs (:test-case-as-pt-test-step options) (:multitestset options) (:testset-name options) false)]
          (case action
            "display-config"
            (do
              (pprint/pprint {"=============== additional-reports: ===============" additional-reports})
              (pprint/pprint {"=============== directory: ===============" directory}))

            "display-options"
            (do
              (pprint/pprint {"=============== options: ===============" options})
              (pprint/pprint {"=============== args: ===============" args}))

            "create-testset"
            (do
              (doall
               (pmap
                (fn [report]
                  (let [testset (create-or-update-sf-testset client options report)]
                    (pprint/pprint (format "Populated TestSet ID: %s" (:id testset)))))
                additional-reports))
              (exit 0 "Done"))

            "populate-testset"
            (do
              (doall
               (pmap
                (fn [report]
                  (populate-sf-results client
                                       options
                                       report))
                additional-reports))
              (exit 0 "Done"))

            "create-and-populate-testset"
            (do
              (doall
               (pmap
                (fn [report]
                  (let [testset (timef
                                  "create-or-update-testset"
                                  (create-or-update-sf-testset client options report))]
                             (timef
                              "populate-results"
                              (populate-sf-results client
                                                   (assoc options
                                                          :skip-validation? true
                                                          :testset-id       (:id testset))
                                                   (:test-list report)))
                    (pprint/pprint (format "Populated TestSet ID: %s" (:id testset))))) additional-reports))
              (exit 0 (format "Done")))
            "test"
            (let [testset (create-or-update-sf-testset client options additional-reports additional-reports)]
              (exit 0 (format "TestSet ID: %s" (:id testset)))))
          ))))
