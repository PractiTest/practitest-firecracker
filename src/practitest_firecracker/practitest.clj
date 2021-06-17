(ns practitest-firecracker.practitest
  (:require
   [clojure.string                   :as string]
   [clojure.set                      :refer [difference]]
   [clojure.walk                     :refer [postwalk]]
   [clj-http.client                  :as http]
   [clojure.tools.logging            :as log]
   [practitest-firecracker.query-dsl :refer [query? eval-query]]
   [throttler.core                   :refer [fn-throttler]]
   [practitest-firecracker.utils     :refer [parse-id print-run-time test-need-update?]]
   [clojure.pprint                   :as     pprint]))

;; ===========================================================================
;; api version
(def ^:const fc-version "2.0.0")

;; ===========================================================================
;; utils

(def backoff-timeout "Backoff timeout in seconds" 20)
(def max-attempts "Number of attempts to run" 10)
(def timeout-between-attempts "Timeout in seconds between attempts" 1)
(def run-batch-bucket-size 10)

(def custom-field-cache (atom {}))

(defn create-api-throttler [rate]
  (let [fn-th (fn-throttler rate :minute)]
    fn-th))

(defn build-uri [base-uri resource-uri-template & params]
  (apply format (str base-uri resource-uri-template) params))

(defn throw-api-exception [ex-info status body uri]
  (throw (ex-info "API request failed" {:status status
                                        :body   body
                                        :uri    uri})))

(defn api-call [{:keys [credentials uri method query-params form-params]}]
  (assert (not (and query-params form-params))
          "both `query-params` and `form-params` can't be specified")
  (loop [results  []
         uri      uri
         attempts max-attempts
         params   (cond-> {:basic-auth          credentials
                           :throw-exceptions    false
                           :as                  :json}
                    query-params (assoc :query-params (conj query-params {:source "firecracker" :firecracker-version fc-version}) )
                    form-params  (assoc :form-params (conj form-params {:source "firecracker" :firecracker-version fc-version}) :content-type :json))]
    (if (nil? uri)
      results
      (let [{:keys [status body]} (method uri params)]
        (if (> attempts 0)
          (case status
            504 (do
                  ;; load balancer freaking out, lets try again
                  (Thread/sleep (* timeout-between-attempts 1000))
                  (recur results uri (dec attempts) params))
            502 (do
                  ;; 502 Bad Gateway Error, lets try again
                  (log/warnf "%s responded with 502 - %s more attempts" uri attempts)
                  (Thread/sleep (* timeout-between-attempts 1000))
                  (recur results uri (dec attempts) params))
            500 (do
                  ;; 500 Bad Gateway Error, lets try again
                  (log/warnf "%s responded with 500 - %s more attempts" uri attempts)
                  (Thread/sleep (* timeout-between-attempts 1000))
                  (recur results uri (dec attempts) params))
            503 (do
                  ;; 503 Service Unavailable, lets try again
                  (log/warnf "%s responded with 503  - %s more attempts" uri attempts)
                  (Thread/sleep (* timeout-between-attempts 1000))
                  (recur results uri (dec attempts) params))
            429 (do
                  (log/warnf "API rate limit reached, waiting for %s seconds" backoff-timeout)
                  (Thread/sleep (* backoff-timeout 1000))
                  (recur results (dec attempts) uri params))
            200 (let [data (:data body)]
                  (recur (vec
                          (if (sequential? data)
                            (concat results data)
                            (conj results data)))
                         (get-in body [:links :next])
                         attempts
                         (dissoc params :query-params)))
            (throw-api-exception ex-info status body uri))
          (throw-api-exception ex-info status body uri))))))

;; ===========================================================================
;; constants

(def ^:const testset-uri "/projects/%d/sets/%d.json")
(def ^:const testset-instances-uri "/projects/%d/instances.json")
(def ^:const test-uri "/projects/%d/tests/%d.json")
(def ^:const test-steps-uri "/projects/%d/steps.json")
(def ^:const create-test-uri "/projects/%d/tests.json")
(def ^:const create-testset-uri "/projects/%d/sets.json")
(def ^:const create-instance-uri "/projects/%d/instances.json")
(def ^:const create-run-uri "/projects/%d/runs.json")
(def ^:const update-test-uri "/projects/%d/tests/%d.json")
(def ^:const update-testset-uri "/projects/%d/sets/%d.json")
(def ^:const list-tests-uri "/projects/%d/tests.json")
(def ^:const bulk-list-tests-uri "/projects/%d/tests/bulk_search.json")
(def ^:const list-testsets-uri "/projects/%d/sets.json")
(def ^:const custom-field-uri "/projects/%d/custom_fields/%d.json")

;; ===========================================================================
;; API

(defn make-client [{:keys [email api-token api-uri max-api-rate]}]
  {:credentials            [email api-token]
   :base-uri               (str api-uri
                                (if (string/ends-with? api-uri "/") "" "/")
                                "api/v2")
   :max-api-rate-throttler (create-api-throttler max-api-rate)})

(defn ll-testset [{:keys [base-uri credentials max-api-rate-throttler]} project-id id]
  ;; (log/infof "create testset %s" id)
  (let [uri (build-uri base-uri testset-uri project-id (if (string? id)
                                                         (Long/parseLong id)
                                                         id))]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/get)}))))

(defn ll-testset-instances [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] testset-id test-ids]
  (when display-action-logs (log/infof "get instances from testsets %s" testset-id))
  (let [uri (build-uri base-uri testset-instances-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       (max-api-rate-throttler http/get)
               :query-params {:set-ids testset-id
                              :test-ids test-ids}})))

(defn ll-test [{:keys [base-uri credentials max-api-rate-throttler]} project-id id]
  ;; (log/infof "create step %s" id)
  (let [uri (build-uri base-uri test-uri project-id id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/get)}))))

(defn ll-test-steps [{:keys [base-uri credentials max-api-rate-throttler]} project-id test-id]
  ;; (log/infof "create step %s" test-id)
  (let [uri (build-uri base-uri test-steps-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       (max-api-rate-throttler http/get)
               :query-params {:test-ids test-id}})))

(defn ll-create-test [{:keys [base-uri credentials max-api-rate-throttler]} project-id attributes steps]
  ;; (log/infof "create test %s" (:name attributes))
  (let [uri (build-uri base-uri create-test-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/post)
                :form-params  {:data {:type       "tests"
                                      :attributes attributes
                                      :steps      {:data steps}}}}))))

(defn ll-create-testset [{:keys [base-uri credentials max-api-rate-throttler]} project-id attributes test-ids]
  ;; (log/infof "create testset %s" (:name attributes))
  (let [uri (build-uri base-uri create-testset-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/post)
                :form-params  {:data {:type       "sets"
                                      :attributes attributes
                                      :instances  {:test-ids test-ids}}}}))))

(defn make-instances [testset-tests]
  (for [[testset-id test-ids-num] testset-tests
        test-id-num test-ids-num
        index (range (last test-id-num))]
    {:type       "instances"
     :attributes {:set-id testset-id
                  :test-id (first test-id-num)}}))

(defn ll-create-instance [{:keys [base-uri credentials max-api-rate-throttler]} project-id testset-id test-id]
  ;; (log/infof "create instance for testset-id %s and test-id %s" testset-id test-id)
  (let [uri (build-uri base-uri create-instance-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/post)
                :form-params  {:data {:type       "instances"
                                      :attributes {:set-id  testset-id
                                                   :test-id test-id}}}}))))

(defn has-duplicates? [key runs]
  (let [grouped (group-by key (into [] runs))]
    (not (= (count runs) (count grouped)))))

(defn ll-create-instances [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] instances]
  (when display-action-logs (log/infof "create instances"))
  (let [uri (build-uri base-uri create-instance-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       (max-api-rate-throttler http/post)
               :form-params  {:data instances}})))

(defn ll-create-run [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] instance-id attributes steps]
  (when display-action-logs (log/infof "create run for instance %s" instance-id))
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/post)
                :form-params  {:data {:type       "instances"
                                      :attributes (merge attributes {:instance-id instance-id})
                                      :steps      {:data steps}}}}))))

(defn ll-create-runs [{:keys [base-uri credentials max-api-rate-throttler] :as client} [project-id display-action-logs] runs]
  (when display-action-logs (log/infof "create runs"))
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (if (has-duplicates? :instance-id runs)
      (let [grouped    (group-by :instance-id runs)
            duplicates (map first (vals (into {} (filter #(> (count (last %)) 1) grouped))))
            uniq       (map first (vals (into {} (filter #(= (count (last %)) 1) grouped))))]
        (do (doall
             (for [run duplicates]
               (ll-create-run {:base-uri base-uri :credentials credentials :max-api-rate-throttler max-api-rate-throttler} [project-id display-action-logs] (:instance-id run) (:attributes run) (:steps run))))
            (ll-create-runs {:base-uri base-uri :credentials credentials :max-api-rate-throttler max-api-rate-throttler} [project-id display-action-logs] uniq)))
      (api-call {:credentials  credentials
                 :uri          uri
                 :method       (max-api-rate-throttler http/post)
                 :form-params  {:data
                                (reduce conj []
                                        (doall (for [run (into () runs)]
                                                 {:type       "instances"
                                                  :attributes (assoc (:attributes run) :instance-id (:instance-id run))
                                                  :steps      {:data (reduce conj [] (:steps run))}})))}}))))


(defn create-runs [runs client options start-time]
  (doall (for [runs-part (partition-all 20 (shuffle runs))]
           (ll-create-runs client [(:project-id options) (:display-action-logs options)] runs-part))))

(defn ll-find-tests [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] name-list]
  (when display-action-logs (log/infof "searching for testsets"))
  (let [uri (build-uri base-uri bulk-list-tests-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       (max-api-rate-throttler http/post)
               :form-params  {:data name-list}})))

(defn ll-find-test [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] name]
  (when display-action-logs (log/infof "searching for test %s" name))
  (let [uri (build-uri base-uri list-tests-uri project-id)]
    ;; in case there are more than one test with this name, return the first one
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/get)
                :query-params {:name_exact name}}))))

(defn ll-find-instance [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] testset-id test-id]
  (when display-action-logs (log/infof "searching for instance by test-id %s and testset-id %s" test-id testset-id))
  (let [uri (build-uri base-uri testset-instances-uri (parse-id project-id))]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/get)
                :query-params {:set-ids  (parse-id testset-id)
                               :test-ids (parse-id test-id)}}))))

(defn ll-find-testset [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] name]
  (when display-action-logs (log/infof "searching for testset %s" name))
  (let [uri (build-uri base-uri list-testsets-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/get)
                :query-params {:name_exact name}}))))

(defn ll-get-custom-field [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] cf-id]
  (when display-action-logs (log/infof "searching custom field %s" cf-id))
  (let [uri (build-uri base-uri custom-field-uri project-id cf-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/get)}))))

(defn ll-update-custom-field [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] cf-id possible-values]
  (when display-action-logs (log/infof "update custom field %s to possible-values: %s" cf-id possible-values))
  (let [uri (build-uri base-uri custom-field-uri project-id cf-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/put)
                :form-params  {:data {:type       "custom_field"
                                      :attributes {:possible-values possible-values}}}}))))

(defn ll-update-test [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] attributes steps cf-id]
  (when display-action-logs (log/infof "update test %s in custom field id %s" (:name attributes) cf-id))
  (let [uri (build-uri base-uri update-test-uri project-id cf-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/put)
                :form-params  {:data {:type       "tests"
                                      :attributes attributes
                                      :steps      {:data steps}}}}))))

(defn ll-update-testset [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] attributes steps cf-id]
  (when display-action-logs (log/infof "update testset %s in field id %s" (:name attributes) cf-id))
  (let [uri (build-uri base-uri update-testset-uri project-id cf-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       (max-api-rate-throttler http/put)
                :form-params  {:data {:type       "sets"
                                      :attributes attributes
                                      :steps      {:data steps}}}}))))

(defn testset [client project-id id]
  (let [testset   (ll-testset client project-id id)
        instances (ll-testset-instances client [project-id true] id)
        tests     (->> instances
                       (map #(get-in % [:attributes :test-id]))
                       (remove nil?)
                       (map #(ll-test client project-id %))
                       (map (fn [{:keys [id] :as t}]
                              (assoc t :steps (ll-test-steps client project-id id)))))]
    {:id         id
     :display-id (get-in testset [:attributes :display-id])
     :name       (get-in testset [:attributes :name])
     :created    (get-in testset [:attributes :created-at])
     :updated    (get-in testset [:attributes :updated-at])
     :tests      (map (fn [test]
                        {:id         (:id test)
                         :display-id (get-in test [:attributes :display-id])
                         :name       (get-in test [:attributes :name])
                         :created    (get-in test [:attributes :created-at])
                         :updated    (get-in test [:attributes :updated-at])
                         :steps      (map (fn [step]
                                            {:id      (:id step)
                                             :name    (get-in step [:attributes :name])
                                             :created (get-in step [:attributes :created-at])
                                             :updated (get-in step [:attributes :updated-at])})
                                          (:steps test))})
                      tests)}))

(defn eval-additional-fields [suite additional-fields]
  (postwalk #(if (query? %) (eval-query suite {} %) %)
            additional-fields))

(defn eval-additional-testset-fields [suite additional-fields]
  (postwalk #(if (query? %) (eval-query suite {} %) %)
            additional-fields))

(defn sf-test-suite->pt-test-name [options suite]
  (let [test-name (eval-query suite {} (:pt-test-name options))]
    (if (string/blank? test-name) "UNNAMED" test-name)))

(defn sf-test-case->pt-step-name [options test-case]
  (let [step-name (eval-query {} test-case (:pt-test-step-name options))]
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
          :let   [cf-id (some-> (last (re-find #"^---f-(\d+)$" (name cf)))
                                (Long/parseLong))]
          :when  (not (nil? cf-id))]
    (when-not (contains? @custom-field-cache cf-id)
      (let [cf (ll-get-custom-field client [project-id display-action-logs] cf-id)]
        (swap! custom-field-cache assoc cf-id [(get-in cf [:attributes :field-format])
                                               (set (get-in cf [:attributes :possible-values]))])))
    (let [[field-format possible-values] (get @custom-field-cache cf-id)]
      (when (= "list" field-format)
        (when-not (contains? possible-values v)
          (swap! custom-field-cache update-in [cf-id 1] conj v)
          (ll-update-custom-field client [project-id display-action-logs] cf-id (vec (conj possible-values v))))))))

(defn create-sf-test [client {:keys [project-id display-action-logs] :as options} sf-test-suite]
  (let [[test-def step-defs]   (sf-test-suite->test-def options sf-test-suite)
        test                   (ll-find-test client [project-id display-action-logs] (:name test-def))
        additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))
        additional-test-fields (merge additional-test-fields (:system-fields additional-test-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-test-fields))
    (if test
      test
      (ll-create-test client
                      project-id
                      (merge test-def
                             {:author-id (:author-id options)}
                             additional-test-fields)
                      step-defs))))

(defn update-sf-test [client {:keys [project-id display-action-logs] :as options} sf-test-suite test-id]
  (let [[test-def step-defs]   (sf-test-suite->test-def options sf-test-suite)
        additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))
        additional-test-fields (merge additional-test-fields (:system-fields additional-test-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-test-fields))
    (ll-update-test client
                    [project-id display-action-logs]
                    (merge test-def
                           {:author-id (:author-id options)}
                           additional-test-fields)
                    step-defs
                    test-id)))

(defn update-sf-testset [client {:keys [project-id display-action-logs] :as options} testset-name sf-test-suite testset-id]
  (let [[test-def step-defs]       (sf-test-suite->test-def options sf-test-suite)
        additional-testset-fields  (:additional-testset-fields options)
        additional-testset-fields  (merge additional-testset-fields (:system-fields additional-testset-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-testset-fields))
    (ll-update-testset client
                       [project-id display-action-logs]
                       (merge test-def
                              {:name testset-name}
                              {:author-id (:author-id options)}
                              additional-testset-fields)
                       step-defs
                       testset-id)))

(defn create-sf-testset [client options sf-test-suites testset-name]
  (let [tests                      (map (partial create-sf-test client options) sf-test-suites)
        additional-testset-fields  (:additional-testset-fields options)
        additional-testset-fields  (merge additional-testset-fields (:system-fields additional-testset-fields))]
    (ll-create-testset client
                       (:project-id options)
                       (merge {:name testset-name}
                              additional-testset-fields)
                       (map :id tests))))

(defn validate-testset [client [project-id display-action-logs] testset-id sf-test-suites]
  ;; check that all tests exist in the given testset
  ;; if not -- throw exception, the user will need to create another testset
  ;; otherwise -- go on
  (let [instances (ll-testset-instances client [project-id display-action-logs] testset-id)
        tests     (map (fn [test-suite]
                         (let [name (:name test-suite)]
                           [name (ll-find-test client [project-id display-action-logs] name)]))
                       sf-test-suites)
        nil-tests (filter #(nil? (last %)) tests)]
    (when (seq nil-tests)
      (throw (ex-info (format "some tests do not exist in PT: %s"
                              (string/join ", " (map first nil-tests)))
                      {})))
    (let [not-in-ts (difference (set (map #(read-string (:id (last %))) tests))
                                (set (map #(get-in % [:attributes :test-id]) instances)))]
      (when (seq not-in-ts)
        (throw (ex-info "some tests are not part of the given testset" {:test-ids not-in-ts}))))
    true))

(defn sf-test-case->run-step-def [options test-case]
  {:name           (sf-test-case->pt-step-name options test-case)
   :actual-results (str (:failure-message test-case) \newline (:failure-detail test-case))
   :status         (case (:failure-type test-case)
                     :failure "FAILED"
                     :skipped "N/A"
                     :error   "FAILED"
                     ;; will leave error as FAILED for now, will change it after we add the UI changes and add the option of ERROR to Reqirement Test and TestSet table of runs
                     nil       "PASSED"
                     "NO RUN")})

(defn sf-test-suite->run-def [options test-suite]
  [{:run-duration (:time-elapsed test-suite)}
   (map (partial sf-test-case->run-step-def options) (:test-cases test-suite))])

(defn make-runs [[test-by-id instance-to-ts-test] client {:keys [project-id display-action-logs] :as options} start-time]
  (when display-action-logs (log/infof "make-runs"))
  (flatten (doall
            (for [[test-testset instances] instance-to-ts-test]
              (for [instance instances]
                (let [[ts-id test-id] test-testset
                      tst (first (get test-by-id test-id))
                      [run run-steps] (sf-test-suite->run-def options (get tst 1))]
                  {:instance-id (:id instance)
                   :attributes run
                   :steps run-steps}))))))

(defn populate-sf-results [client {:keys [project-id testset-id display-action-logs] :as options} sf-test-suites]
  (when display-action-logs (log/infof "populating testset %s with results from %d suites" testset-id (count sf-test-suites)))
  (when (or (:skip-validation? options)
            (validate-testset client project-id testset-id sf-test-suites))
    (doall
     (pmap (fn [test-suite]
             (let [test-name       (sf-test-suite->pt-test-name options test-suite)
                   log             (if display-action-logs (log/infof "instance test-name: %s " test-name) nil)
                   test            (ll-find-test client [project-id display-action-logs] test-name)
                   instance        (ll-find-instance client [project-id display-action-logs] testset-id (:id test))
                   [run run-steps] (sf-test-suite->run-def options test-suite)]
               (ll-create-run client [project-id display-action-logs] (:id instance) run run-steps)))
           sf-test-suites))
    true))

(defn find-sf-testset [client [project-id display-action-logs] options testset-name]
  (let [testset (ll-find-testset client [project-id display-action-logs] testset-name)]
    (when testset
      (update-sf-testset client options testset-name testset (read-string (:id testset))))))

(defn create-testsets [client {:keys [project-id display-action-logs] :as options} xml]
  (doall
   (for [sf-test-suites xml]
     (let [testset-name (or (:name (:attrs sf-test-suites) (:name sf-test-suites)))
           testset      (or (find-sf-testset client [project-id display-action-logs] options testset-name)
                            (create-sf-testset client options (:test-cases sf-test-suites) testset-name))]
       {(:id testset) (:test-list sf-test-suites)}))))

(defn group-tests [testsets client options]
  (let [ts-id-test-name-num-instances (into {} (map (fn [[k v]]
                                                      {k
                                                       (frequencies (map (fn [test] (sf-test-suite->pt-test-name options test)) v))})
                                                    (into {} testsets)))
        testset-id-to-name (map (fn [[k v]]
                                    {k (set (map
                                             (fn [test] (sf-test-suite->pt-test-name options test)) v))})
                                  (into {} testsets))
        tests                (flatten (map val (into {} testsets)))
        all-tests            (doall (into {} (for [test tests]
                                               {(sf-test-suite->pt-test-name options test) test})))]
    [all-tests testset-id-to-name ts-id-test-name-num-instances]))

(defn create-or-update-tests [[all-tests testset-id-to-name ts-id-test-name-num-instances] client {:keys [project-id display-action-logs display-run-time] :as options} start-time]
  (let [new-tests  (into [] (group-test-names (vals all-tests) options))
        results    (doall
                    (flatten
                     (into []
                           (pmap
                            (fn [new-tests-part]
                              (ll-find-tests client [project-id display-action-logs] new-tests-part)) (partition-all 20 (shuffle new-tests))))))
        tests (doall (for [res results]
                       [(:name_exact (:query res)) (get all-tests (:name_exact (:query res))) (first (:data (:tests res)))]))
        log       (if display-run-time (print-run-time "Time - after find all tests: %d:%d:%d" start-time) nil)
        nil-tests (filter #(nil? (last %)) tests)
        old-tests (filter #(not (nil? (last %))) tests)

        tests-after (if (seq nil-tests)
                      ;; create missing tests and add them to the testset
                      (let [new-tests (pmap (fn [[test-name test-suite _]]
                                              [test-name test-suite (create-sf-test client options test-suite)])
                                            nil-tests)]
                        new-tests)
                      ())
        log       (if display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time) nil)
        new-all-tests (concat tests-after old-tests)]
    (when (seq old-tests)
      ;; update existing tests with new values
      (do
        (doall (map (fn [[_ test-suite test]]
                      (when (test-need-update? test-suite test)
                        (update-sf-test client options test-suite (read-string (:id test)))))
                    old-tests))
        (when display-run-time (print-run-time "Time - after update tests: %d:%d:%d" start-time))))
    [new-all-tests testset-id-to-name ts-id-test-name-num-instances]))

(defn create-instances [[all-tests testset-id-to-name ts-id-test-name-num-instances] client {:keys [project-id display-action-logs display-run-time] :as options} start-time]
  (let [all-test-ids    (map (fn [test] (:id (last test))) all-tests)
        testname-test   (into {} (map (fn [test] {(first test) test}) all-tests))
        test-ids        (string/join "," all-test-ids)
        testset-ids     (map (fn [testset] (first (first testset))) testset-id-to-name)
        ts-ids          (string/join "," testset-ids)
        instances       (ll-testset-instances client [project-id display-action-logs] ts-ids test-ids)

        ts-id-instance-num   (into {} (map (fn [testset-id-name]
                                             {(first (first testset-id-name))
                                              (into {} (map (fn [test-name]
                                                     {(read-string (:id (last (get testname-test test-name))))
                                                      (get (get ts-id-test-name-num-instances (first (first testset-id-name))) test-name)})
                                                   (last (last testset-id-name))))})
                                           testset-id-to-name))
        ts-id-instances (group-by (fn [inst] (get-in inst [:attributes :set-id])) instances)

        missing-tests   (into {}
                              (doall
                               (for [ts-id (into () testset-ids)]
                                 {ts-id (merge-with -
                                                    (get ts-id-instance-num ts-id)
                                                    (frequencies (vec (map #(get-in % [:attributes :test-id]) (get ts-id-instances (read-string ts-id))))))})))
        make-instances  (flatten (make-instances missing-tests))
        test-by-id      (group-by (fn [test] (read-string (:id (last test)))) all-tests)
        new-intstances  (flatten (for [instances-part (partition-all 100 (shuffle make-instances))]
                                   (ll-create-instances client [project-id display-action-logs] instances-part)))
        all-intstances  (into [] (concat new-intstances instances))
        instance-to-ts-test (group-by (fn [inst] [(:set-id (:attributes inst)) (:test-id (:attributes inst))]) all-intstances)]
      (when display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time))
      [test-by-id instance-to-ts-test]))
