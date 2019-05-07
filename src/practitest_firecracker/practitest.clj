(ns practitest-firecracker.practitest
  (:require
   [clojure.string                   :as string]
   [clojure.set                      :refer [difference]]
   [clojure.walk                     :refer [postwalk]]
   [clj-http.client                  :as http]
   [clojure.tools.logging            :as log]
   [practitest-firecracker.query-dsl :refer [query? eval-query]]))

;; ===========================================================================
;; utils

(def backoff-timeout "Backoff timeout in seconds" 20)
(def run-batch-bucket-size 10)

(def custom-field-cache (atom {}))

(defn build-uri [base-uri resource-uri-template & params]
  (apply format (str base-uri resource-uri-template) params))

(defn api-call [{:keys [credentials uri method query-params form-params]}]
  (assert (not (and query-params form-params))
          "both `query-params` and `form-params` can't be specified")
  (loop [results []
         uri     uri
         params  (cond-> {:basic-auth credentials
                          :throw-exceptions false
                          :as :json}
                   query-params (assoc :query-params query-params)
                   form-params  (assoc :form-params form-params :content-type :json))]
    (if (nil? uri)
      results
      (let [{:keys [status body]} (method uri params)]
        (case status
          504 (do
                ;; load balancer freaking out, lets try again
                (Thread/sleep 1000)
                (recur results uri params))
          429 (do
                (log/warnf "API rate limit reached, waiting for %s seconds" backoff-timeout)
                (Thread/sleep (* backoff-timeout 1000))
                (recur results uri params))
          200 (let [data (:data body)]
                (recur (vec
                        (if (sequential? data)
                          (concat results data)
                          (conj results data)))
                       (get-in body [:links :next])
                       (dissoc params :query-params)))
          (throw (ex-info "API request failed" {:status status
                                                :body   body
                                                :uri    uri})))))))

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
(def ^:const list-tests-uri "/projects/%d/tests.json")
(def ^:const list-testsets-uri "/projects/%d/sets.json")
(def ^:const custom-field-uri "/projects/%d/custom_fields/%d.json")

;; ===========================================================================
;; API

(defn make-client [{:keys [email api-token api-uri]}]
  {:credentials [email api-token]
   :base-uri    (str api-uri
                     (if (string/ends-with? api-uri "/") "" "/")
                     "api/v2")})

(defn ll-testset [{:keys [base-uri credentials]} project-id id]
  (let [uri (build-uri base-uri testset-uri project-id id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/get}))))

(defn ll-testset-instances [{:keys [base-uri credentials]} project-id testset-id]
  (let [uri (build-uri base-uri testset-instances-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       http/get
               :query-params {:set-ids testset-id}})))

(defn ll-test [{:keys [base-uri credentials]} project-id id]
  (let [uri (build-uri base-uri test-uri project-id id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/get}))))

(defn ll-test-steps [{:keys [base-uri credentials]} project-id test-id]
  (let [uri (build-uri base-uri test-steps-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       http/get
               :query-params {:test-ids test-id}})))

(defn ll-create-test [{:keys [base-uri credentials]} project-id attributes steps]
  (let [uri (build-uri base-uri create-test-uri project-id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/post
                :form-params {:data {:type       "tests"
                                     :attributes attributes
                                     :steps      {:data steps}}}}))))

(defn ll-create-testset [{:keys [base-uri credentials]} project-id attributes test-ids]
  (let [uri (build-uri base-uri create-testset-uri project-id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/post
                :form-params {:data {:type       "sets"
                                     :attributes attributes
                                     :instances  {:test-ids test-ids}}}}))))

(defn ll-create-instance [{:keys [base-uri credentials]} project-id testset-id test-id]
  ;; TODO: bulk-create instances (same set id, multiple test ids)
  (let [uri (build-uri base-uri create-instance-uri project-id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/post
                :form-params {:data {:type       "instances"
                                     :attributes {:set-id  testset-id
                                                  :test-id test-id}}}}))))

(defn ll-create-run [{:keys [base-uri credentials]} project-id instance-id attributes steps]
  (log/info :ll-create-run project-id instance-id)
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/post
                :form-params {:data {:type       "instances"
                                     :attributes (assoc attributes :instance-id instance-id)
                                     :steps      {:data steps}}}}))))

(defn ll-create-runs [{:keys [base-uri credentials]} project-id runs]
  (log/info :ll-create-runs project-id (map first runs))
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/post
               :form-params {:data (for [[instance-id attributes steps] runs]
                                     {:type       "instances"
                                      :attributes (assoc attributes :instance-id instance-id)
                                      :steps      {:data steps}})}})))

(defn ll-find-test* [{:keys [base-uri credentials]} project-id name]
  (let [uri (build-uri base-uri list-tests-uri project-id)]
    ;; in case there are more than one test with this name, return the first one
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       http/get
                :query-params {:name_exact name}}))))

(def ll-find-test (memoize ll-find-test*))

(defn ll-find-instance* [{:keys [base-uri credentials]} project-id testset-id test-id]
  (let [uri (build-uri base-uri testset-instances-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       http/get
                :query-params {:set-ids  testset-id
                               :test-ids test-id}}))))

(def ll-find-instance (memoize ll-find-instance*))

(defn ll-find-testset* [{:keys [base-uri credentials]} project-id name]
  (let [uri (build-uri base-uri list-testsets-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       http/get
                :query-params {:name_exact name}}))))

(def ll-find-testset (memoize ll-find-testset*))

(defn ll-get-custom-field [{:keys [base-uri credentials]} project-id cf-id]
  (let [uri (build-uri base-uri custom-field-uri project-id cf-id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/get}))))

(defn ll-update-custom-field [{:keys [base-uri credentials]} project-id cf-id possible-values]
  (let [uri (build-uri base-uri custom-field-uri project-id cf-id)]
    (first
     (api-call {:credentials credentials
                :uri         uri
                :method      http/put
                :form-params {:data {:type       "custom_field"
                                     :attributes {:possible-values possible-values}}}}))))

(defn testset [client project-id id]
  (let [testset   (ll-testset client project-id id)
        instances (ll-testset-instances client project-id id)
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

(defn sf-test-suite->pt-test-name [options suite]
  (let [test-name (eval-query suite {} (:pt-test-name options))]
    (if (string/blank? test-name) "UNNAMED" test-name)))

(defn sf-test-case->pt-step-name [options test-case]
  (let [step-name (eval-query {} test-case (:pt-test-step-name options))]
    (if (string/blank? step-name) "UNNAMED" step-name)))

(defn sf-test-case->step-def-old [test-case]
  {:name (:full-name test-case)})

(defn sf-test-suite->test-def-old [test-suite]
  [{:name (str (:package-name test-suite) ":" (:name test-suite))}
   (map sf-test-case->step-def-old (:test-cases test-suite))])

(defn create-sf-test-old [client project-id author-id additional-fields sf-test-suite]
  (let [[test-def step-defs] (sf-test-suite->test-def-old sf-test-suite)
        test                 (ll-find-test client project-id (:name test-def))]
    (if test
      test
      (ll-create-test client project-id (merge test-def {:author-id author-id} additional-fields) step-defs))))

(defn sf-test-case->step-def [options test-case]
  {:name (sf-test-case->pt-step-name options test-case)})

(defn sf-test-suite->test-def [options test-suite]
  [{:name (sf-test-suite->pt-test-name options test-suite)}
   (map (partial sf-test-case->step-def options) (:test-cases test-suite))])

(defn ensure-custom-field-values [client project-id custom-fields]
  (doseq [[cf v] custom-fields
          :let [cf-id (some-> (last (re-find #"^---f-(\d+)$" (name cf)))
                              (Long/parseLong))]
          :when (not (nil? cf-id))]
    (when-not (contains? @custom-field-cache cf-id)
      (let [cf (ll-get-custom-field client project-id cf-id)]
        (swap! custom-field-cache assoc cf-id [(get-in cf [:attributes :field-format])
                                               (set (get-in cf [:attributes :possible-values]))])))
    (let [[field-format possible-values] (get @custom-field-cache cf-id)]
      (when (= "list" field-format)
        (when-not (contains? possible-values v)
          (swap! custom-field-cache update-in [cf-id 1] conj v)
          (ll-update-custom-field client project-id cf-id (vec (conj possible-values v))))))))

(defn create-sf-test [client {:keys [project-id] :as options} sf-test-suite]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        test                 (ll-find-test client project-id (:name test-def))]
    (if test
      test
      (let [additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))]
        (ensure-custom-field-values client project-id (:custom-fields additional-test-fields))
        (ll-create-test client
                        project-id
                        (merge test-def
                               {:author-id (:author-id options)}
                               additional-test-fields)
                        step-defs)))))

(defn create-sf-testset-old [client project-id author-id additional-test-fields sf-name additional-testset-fields sf-test-suites]
  (let [tests (pmap (partial create-sf-test client project-id author-id additional-test-fields) sf-test-suites)]
    (ll-create-testset client project-id (merge {:name sf-name} additional-testset-fields) (map :id tests))))

(defn create-sf-testset [client options sf-test-suites]
  (let [tests (pmap (partial create-sf-test client options) sf-test-suites)]
    (ll-create-testset client
                       (:project-id options)
                       (merge {:name (:testset-name options)} (:additional-testset-fields options))
                       (map :id tests))))

(defn sf-test-case->run-step-def-old [test-case]
  {:name           (:full-name test-case)
   :actual-results (str (:failure-message test-case) \newline (:failure-detail test-case))
   :status         (if (:has-failure? test-case) "FAILED" "PASSED")})

(defn sf-test-suite->run-def-old [test-suite]
  [{:run-duration (:time-elapsed test-suite)}
   (map sf-test-case->run-step-def-old (:test-cases test-suite))])

(defn validate-testset [client project-id testset-id sf-test-suites]
  ;; check that all tests exist in the given testset
  ;; if not -- throw exception, the user will need to create another testset
  ;; otherwise -- go on
  (let [instances (ll-testset-instances client project-id testset-id)
        tests     (pmap (fn [test-suite]
                          (let [name (str (:package-name test-suite) ":" (:name test-suite))]
                            [name (ll-find-test client project-id name)]))
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

(defn populate-sf-results-old [client project-id testset-id sf-test-suites]
  (log/infof "populating testset %s with results from %d suites" testset-id (count sf-test-suites))
  (when (validate-testset client project-id testset-id sf-test-suites)
    (doall
     (pmap (fn [sf-test-suite]
             (let [test-name       (str (:package-name sf-test-suite) ":" (:name sf-test-suite))
                   test            (ll-find-test client project-id test-name)
                   instance        (ll-find-instance client project-id testset-id (:id test))
                   [run run-steps] (sf-test-suite->run-def-old sf-test-suite)]
               (ll-create-run client project-id (:id instance) run run-steps)))
           sf-test-suites))
    true))

(defn sf-test-case->run-step-def [options test-case]
  {:name           (sf-test-case->pt-step-name options test-case)
   :actual-results (str (:failure-message test-case) \newline (:failure-detail test-case))
   :status         (if (:has-failure? test-case) "FAILED" "PASSED")})

(defn sf-test-suite->run-def [options test-suite]
  [{:run-duration (:time-elapsed test-suite)}
   (map (partial sf-test-case->run-step-def options) (:test-cases test-suite))])

(defn populate-sf-results [client {:keys [project-id testset-id] :as options} sf-test-suites]
  (log/infof "populating testset %s with results from %d suites" testset-id (count sf-test-suites))
  (when (or (:skip-validation? options)
            (validate-testset client project-id testset-id sf-test-suites))
    (doall
     (pmap (fn [test-suite]
             (let [test-name       (sf-test-suite->pt-test-name options test-suite)
                   test            (ll-find-test client project-id test-name)
                   instance        (ll-find-instance client project-id testset-id (:id test))
                   [run run-steps] (sf-test-suite->run-def options test-suite)]
               (log/info (pr-str [testset-id (:id test)]))
               (log/info (pr-str instance))
               (ll-create-run client project-id (:id instance) run run-steps)))
           sf-test-suites))
    true))

(defn find-sf-testset [client project-id testset-name]
  (ll-find-testset client project-id testset-name))

(defn create-or-update-sf-testset [client {:keys [project-id] :as options} sf-test-suites]
  (if-let [testset (find-sf-testset client project-id (:testset-name options))]
    (let [instances (ll-testset-instances client project-id (:id testset))
          tests     (pmap (fn [test-suite]
                            (let [test-name (sf-test-suite->pt-test-name options test-suite)]
                              [test-name test-suite (ll-find-test client project-id test-name)]))
                          sf-test-suites)
          nil-tests (filter #(nil? (last %)) tests)]
      (when (seq nil-tests)
        ;; create missing tests and add them to the testset
        (let [new-tests (pmap (fn [[_ test-suite _]]
                                (create-sf-test client options test-suite))
                              nil-tests)]
          (doall
           (pmap #(ll-create-instance client project-id (:id testset) (:id %)) new-tests))))
      ;; add any missing instances to the testset
      (let [missing-tests (difference (set (map #(read-string (:id (last %))) (remove #(nil? (last %)) tests)))
                                      (set (map #(get-in % [:attributes :test-id]) instances)))]
        (doall
         (pmap #(ll-create-instance client project-id (:id testset) %) missing-tests)))
      testset)
    (create-sf-testset client options sf-test-suites)))
