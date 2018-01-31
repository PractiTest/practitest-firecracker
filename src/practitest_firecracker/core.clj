(ns practitest-firecracker.core
  (:require
   [practitest-firecracker.cli        :refer [parse-args]]
   [practitest-firecracker.practitest :refer [make-client create-sf-testset populate-sf-results create-or-update-sf-testset]]
   [practitest-firecracker.surefire   :refer [parse-reports-dir]])
  (:gen-class))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (parse-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [client  (make-client (select-keys options [:email :api-token :api-uri]))
            reports (parse-reports-dir (:reports-path options))]
        (case action
          "create-testset"
          (let [testset (create-sf-testset client
                                           (:project-id options)
                                           (:author-id options)
                                           (:additional-test-fields options)
                                           (:testset-name options)
                                           (:additional-testset-fields options)
                                           reports)]
            (exit 0 (format "TestSet ID: %s" (:id testset))))

          "populate-testset"
          (do
            (populate-sf-results client
                                 (:project-id options)
                                 (:testset-id options)
                                 reports)
            (exit 0 "Done"))

          "create-and-populate-testset"
          (let [testset (create-or-update-sf-testset client
                                                     (:project-id options)
                                                     (:author-id options)
                                                     (:additional-test-fields options)
                                                     (:testset-name options)
                                                     (:additional-testset-fields options)
                                                     reports)]
            (populate-sf-results client
                                 (:project-id options)
                                 (:id testset)
                                 reports)
            (exit 0 (format "Populated TestSet ID: %s" (:id testset)))))))))
