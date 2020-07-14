(ns practitest-firecracker.core
  (:require
   [practitest-firecracker.cli        :refer [parse-args]]
   [practitest-firecracker.practitest :refer [make-client
                                              create-sf-testset
                                              populate-sf-results-old
                                              populate-sf-results
                                              create-or-update-sf-testset]]
   [practitest-firecracker.surefire   :refer [parse-reports-dir]]
   [test-xml-parser.core              :refer [send-directory remove-bom return-files delete-recursively!]]
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

(defn clean-tmp-folder [directory]
  (doseq [path directory]
    (delete-recursively! path)))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (parse-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do
        (doseq [report-path (:reports-path options)]
          (remove-bom report-path))
        (let [client             (make-client (select-keys options [:email :api-token :api-uri :max-api-rate]))
              directory          (map #(.getAbsolutePath (file "tmp" %)) (:reports-path options))
              reports            (parse-reports-dir directory)
              additional-reports1 (send-directory (first directory) reports)
              additional-reports2 (send-directory (last directory) reports)
              report-result       (list )
              additional-reports  (first
                                   (conj report-result
                                         (first
                                          (for [dir directory]
                                            (send-directory dir reports)))))
              ]
          (case action
            "display-config"
            (do
              (pprint/pprint {"=============== additional-reports1: ===============" additional-reports1})
              (pprint/pprint {"=============== additional-reports2: ===============" additional-reports2})
              (pprint/pprint {"=============== additional-reports: ===============" additional-reports})
              (pprint/pprint {"=============== FC original reports val: ===============" reports})
              ;; (pprint/pprint {"=============== directory: ===============" directory})
              (clean-tmp-folder directory))

            "display-options"
            (do
              (pprint/pprint {"=============== options: ===============" options})
              (pprint/pprint {"=============== args: ===============" args})
              (clean-tmp-folder directory))

            "create-testset"
            (let [testset (create-or-update-sf-testset client options additional-reports)]
              (clean-tmp-folder directory)
              (exit 0 (format "TestSet ID: %s" (:id testset))))

            "populate-testset"
            (do
              (populate-sf-results client
                                   options
                                   additional-reports)
              (clean-tmp-folder directory)
              (exit 0 "Done"))

            "create-and-populate-testset"
            (let [testset (timef
                           "create-or-update-testset"
                           (create-or-update-sf-testset client options additional-reports))]
              (timef
               "populate-results"
               (populate-sf-results client
                                    (assoc options
                                           :skip-validation? true
                                           :testset-id       (:id testset))
                                    additional-reports))
              (clean-tmp-folder directory)
              (exit 0 (format "Populated TestSet ID: %s" (:id testset))))
            "test"
            (let [testset (create-or-update-sf-testset client options additional-reports additional-reports)]
              (clean-tmp-folder directory)
              (exit 0 (format "TestSet ID: %s" (:id testset)))))
          )))))
