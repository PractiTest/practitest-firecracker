(ns practitest-firecracker.eval
  (:require
    [clojure.string :as string]
    [practitest-firecracker.query-dsl :refer [query? eval-query]]
    [clojure.walk :refer [postwalk]]
    [clojure.tools.logging :as log]
    [practitest-firecracker.utils :refer [pformat]]
    [practitest-firecracker.api :as api]))

(def custom-field-cache (atom {}))

(defn eval-additional-fields [suite additional-fields]
  (postwalk #(if (query? %) (eval-query suite %) %)
            additional-fields))

(defn sf-test-suite->pt-test-name [options suite]
  (let [test-name (eval-query suite (:pt-test-name options))]
    (if (string/blank? test-name) "UNNAMED" test-name)))

(defn sf-test-case->pt-step-name [options test-case]
  (let [step-name (eval-query test-case (:pt-test-step-name options))]
    (if (string/blank? step-name) "UNNAMED" step-name)))

(defn sf-test-case->step-def [options test-case]
  {:name (sf-test-case->pt-step-name options test-case)})

(defn sf-test-suite->test-def [options test-suite]
  [{:name (sf-test-suite->pt-test-name options test-suite)}
   (map (partial sf-test-case->step-def options) (:test-cases test-suite))])

(defn group-test-names [tests options]
  (doall (for [test tests]
           {:name_exact (sf-test-suite->pt-test-name options test)})))

(defn ensure-custom-field-values [client [project-id display-action-logs] custom-fields]
  (doseq [[cf v] custom-fields
          :let [cf-id (some-> (last (re-find #"^---f-(\d+)$" (name cf)))
                              (Long/parseLong))]
          :when (not (nil? cf-id))]
    (when-not (contains? @custom-field-cache cf-id)
      (let [cf (api/ll-get-custom-field client [project-id display-action-logs] cf-id)]
        (swap! custom-field-cache assoc cf-id [(get-in cf [:attributes :field-format])
                                               (set (get-in cf [:attributes :possible-values]))])))
    (let [[field-format possible-values] (get @custom-field-cache cf-id)]
      (when (= "list" field-format)
        (when-not (contains? possible-values v)
          (swap! custom-field-cache update-in [cf-id 1] conj v)
          (api/ll-update-custom-field client [project-id display-action-logs] cf-id (vec (conj possible-values v))))))))

(defn create-sf-test [client {:keys [project-id display-action-logs] :as options} sf-test-suite]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        test (api/ll-find-test client [project-id display-action-logs] (:name test-def))
        additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))
        additional-test-fields (merge additional-test-fields (:system-fields additional-test-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-test-fields))
    (if test
      test
      (api/ll-create-test client
                      project-id
                      (merge test-def
                             {:author-id (:author-id options)}
                             additional-test-fields)
                      step-defs))))

(defn update-sf-test [client {:keys [project-id display-action-logs] :as options} sf-test-suite test]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))
        additional-test-fields (merge additional-test-fields (:system-fields additional-test-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-test-fields))
    (api/ll-update-test client
                    [project-id display-action-logs]
                    (merge test-def
                           {:author-id (:author-id options)
                            :test-type (:test-type (:attributes test))}
                           additional-test-fields)
                    step-defs
                    (read-string (:id test)))))

(defn update-sf-testset [client {:keys [project-id display-action-logs] :as options} testset-name sf-test-suite testset-id]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        additional-testset-fields (:additional-testset-fields options)
        additional-testset-fields (merge additional-testset-fields (:system-fields additional-testset-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-testset-fields))
    (api/ll-update-testset client
                       [project-id display-action-logs]
                       (merge test-def
                              {:name testset-name}
                              {:author-id (:author-id options)}
                              additional-testset-fields)
                       step-defs
                       testset-id)))

(defn create-sf-testset [client options sf-test-suites testset-name]
  (let [tests (map (partial create-sf-test client options) sf-test-suites)
        additional-testset-fields (:additional-testset-fields options)
        additional-testset-fields (merge additional-testset-fields (:system-fields additional-testset-fields))]
    (api/ll-create-testset client
                       (:project-id options)
                       (merge {:name testset-name}
                              additional-testset-fields)
                       (map :id tests))))

(defn sf-test-case->run-step-def [options test-case]
  {:name           (sf-test-case->pt-step-name options test-case)
   :actual-results (str (:failure-message test-case) \newline (:failure-detail test-case))
   :status         (case (:failure-type test-case)
                     :failure "FAILED"
                     :skipped "N/A"
                     :error "FAILED"
                     ;; will leave error as FAILED for now, will change it after we add the UI changes and add the option of ERROR to Reqirement Test and TestSet table of runs
                     nil "PASSED"
                     "NO RUN")
   :description    (:description test-case)})

(defn sf-test-suite->run-def [options test-suite]
  [{:run-duration (:time-elapsed test-suite)}
   (map (partial sf-test-case->run-step-def options) (:test-cases test-suite))])

(defn sf-test-run->run-def [custom-fields run-duration]
  {:run-duration  (:time-elapsed run-duration),
   :custom-fields (:custom-fields custom-fields)})