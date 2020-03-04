(ns practitest-firecracker.core
  (:require
   [practitest-firecracker.cli        :refer [parse-args]]
   [practitest-firecracker.practitest :refer [make-client
                                              create-sf-testset
                                              populate-sf-results-old
                                              populate-sf-results
                                              create-or-update-sf-testset]]
   [practitest-firecracker.surefire   :refer [parse-reports-dir]]
   [test-xml-parser.core              :refer [send-directory]]
   [clojure.zip :as zip]
   [clojure.xml :as xml]
   [clojure.pprint :as pprint]
   [clojure.pprint :as p]
   )
  (:gen-class)
  (:import java.io.File))

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

(defn zip-str [s]
  (zip/xml-zip
   (xml/parse (java.io.ByteArrayInputStream. (.getBytes s)))))

;; A common task it to load a file into a byte array.
(defn file->bytes [file]
  (with-open [xin (clojure.java.io/input-stream file)
              xout (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy xin xout)
    (.toByteArray xout)))
                                        ;=> #'boot.user/file->bytes

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (parse-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [client  (make-client (select-keys options [:email :api-token :api-uri]))
            reports (parse-reports-dir (:reports-path options))
            config  (parse-reports-dir (:reports-path options))
            directory (clojure.java.io/file (first (:reports-path options)))
            additional-config  (send-directory directory config)
            additional-reports (send-directory directory reports)]
        (case action
          "display-config"
          (let [result (send-directory directory reports)]
            (pprint/pprint {"=============== FC original reports val: ===============" reports})
            (pprint/pprint {"=============== FC result: ===============" result}))

          "create-testset"
          (let [testset (create-or-update-sf-testset client options additional-reports)]
            (exit 0 (format "TestSet ID: %s" (:id testset))))

          "populate-testset"
          (do
            (populate-sf-results-old client
                                     (:project-id options)
                                     (:testset-id options)
                                     additional-reports)
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
            (exit 0 (format "Populated TestSet ID: %s" (:id testset))))
          "test"
          (let [testset (create-or-update-sf-testset client options additional-reports additional-config)]
            (exit 0 (format "TestSet ID: %s" (:id testset)))))))))
