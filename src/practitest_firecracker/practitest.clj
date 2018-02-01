(ns practitest-firecracker.practitest
  (:require
   [clojure.string  :as string]
   [clojure.set     :refer [difference]]
   [clj-http.client :as http]))

;; ===========================================================================
;; utils

(defn build-uri [base-uri resource-uri-template & params]
  (apply format (str base-uri resource-uri-template) params))

(defn api-call [{:keys [credentials uri method query-params form-params]}]
  (assert (not (and query-params form-params))
          "both `query-params` and `form-params` can't be specified")
  (-> (method uri (cond-> {:basic-auth credentials :as :json}
                    query-params (assoc :query-params query-params)
                    form-params  (assoc :form-params form-params :content-type :json)))
      :body
      :data))

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

;; ===========================================================================
;; API

(defn make-client [{:keys [email api-token api-uri]}]
  {:credentials [email api-token]
   :base-uri    (str api-uri
                     (if (string/ends-with? api-uri "/") "" "/")
                     "api/v2")})

(defn ll-testset [{:keys [base-uri credentials]} project-id id]
  (let [uri (build-uri base-uri testset-uri project-id id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/get})))

(defn ll-testset-instances [{:keys [base-uri credentials]} project-id testset-id]
  ;; TODO: support testsets with more than 100 instances (currently returns only first 100)
  (let [uri (build-uri base-uri testset-instances-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       http/get
               :query-params {:set-ids testset-id}})))

(defn ll-test [{:keys [base-uri credentials]} project-id id]
  (let [uri (build-uri base-uri test-uri project-id id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/get})))

(defn ll-test-steps [{:keys [base-uri credentials]} project-id test-id]
  (let [uri (build-uri base-uri test-steps-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       http/get
               :query-params {:test-ids test-id}})))

(defn ll-create-test [{:keys [base-uri credentials]} project-id attributes steps]
  (let [uri (build-uri base-uri create-test-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/post
               :form-params {:data {:type       "tests"
                                    :attributes attributes
                                    :steps      {:data steps}}}})))

(defn ll-create-testset [{:keys [base-uri credentials]} project-id attributes test-ids]
  (let [uri (build-uri base-uri create-testset-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/post
               :form-params {:data {:type       "sets"
                                    :attributes attributes
                                    :instances  {:test-ids test-ids}}}})))

(defn ll-create-instance [{:keys [base-uri credentials]} project-id testset-id test-id]
  (let [uri (build-uri base-uri create-instance-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/post
               :form-params {:data {:type       "instances"
                                    :attributes {:set-id  testset-id
                                                 :test-id test-id}}}})))

(defn ll-create-run [{:keys [base-uri credentials]} project-id instance-id attributes steps]
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      http/post
               :form-params {:data {:type       "instances"
                                    :attributes (assoc attributes :instance-id instance-id)
                                    :steps      {:data steps}}}})))

(defn ll-find-test [{:keys [base-uri credentials]} project-id name]
  (let [uri (build-uri base-uri list-tests-uri project-id)]
    ;; in case there are more than one test with this name, return the first one
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       http/get
                :query-params {:name_exact name}}))))

(defn ll-find-instance [{:keys [base-uri credentials]} project-id testset-id test-id]
  (let [uri (build-uri base-uri testset-instances-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       http/get
                :query-params {:set-ids  testset-id
                               :test-ids test-id}}))))

(defn ll-find-testset [{:keys [base-uri credentials]} project-id name]
  (let [uri (build-uri base-uri list-testsets-uri project-id)]
    (first
     (api-call {:credentials  credentials
                :uri          uri
                :method       http/get
                :query-params {:name_exact name}}))))

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

(defn sf-test-case->step-def [test-case]
  {:name (:full-name test-case)})

(defn sf-test-suite->test-def [test-suite]
  [{:name (str (:package-name test-suite) ":" (:name test-suite))}
   (map sf-test-case->step-def (:test-cases test-suite))])

(defn create-sf-test [client project-id author-id additional-fields sf-test-suite]
  (let [[test-def step-defs] (sf-test-suite->test-def sf-test-suite)
        test                 (ll-find-test client project-id (:name test-def))]
    (if test
      test
      (ll-create-test client project-id (merge test-def {:author-id author-id} additional-fields) step-defs))))

(defn create-sf-testset [client project-id author-id additional-test-fields sf-name additional-testset-fields sf-test-suites]
  (let [tests (map (partial create-sf-test client project-id author-id additional-test-fields) sf-test-suites)]
    (ll-create-testset client project-id (merge {:name sf-name} additional-testset-fields) (map :id tests))))

(defn sf-test-case->run-step-def [test-case]
  {:name           (:full-name test-case)
   :actual-results (:failure-message test-case)
   :status         (if (:has-failure? test-case) "FAILED" "PASSED")})

(defn sf-test-suite->run-def [test-suite]
  [{:run-duration (:time-elapsed test-suite)}
   (map sf-test-case->run-step-def (:test-cases test-suite))])

(defn validate-testset [client project-id testset-id sf-test-suites]
  ;; check that all tests exist in the given testset
  ;; if not -- throw exception, the user will need to create another testset
  ;; otherwise -- go on
  (let [instances (ll-testset-instances client project-id testset-id)
        tests     (map (fn [test-suite]
                         (let [name (str (:package-name test-suite) ":" (:name test-suite))]
                           [name (ll-find-test client project-id name)]))
                       sf-test-suites)
        nil-tests (filter #(nil? (last %)) tests)]
    (when (seq nil-tests)
      (throw (ex-info (format "some tests do not exist in PT: %s"
                              (string/join ", " (map first nil-tests)))
                      {})))
    (when (seq (difference (set (map #(read-string (:id (last %))) tests))
                           (set (map #(get-in % [:attributes :test-id]) instances))))
      (throw (ex-info "some tests are not part of the given testset" {})))
    true))

(defn populate-sf-results [client project-id testset-id sf-test-suites]
  (when (validate-testset client project-id testset-id sf-test-suites)
    (doseq [sf-test-suite sf-test-suites
            :let [test-name (str (:package-name sf-test-suite) ":" (:name sf-test-suite))
                  test      (ll-find-test client project-id test-name)
                  instance  (ll-find-instance client project-id testset-id (:id test))
                  [run run-steps] (sf-test-suite->run-def sf-test-suite)]]
      (ll-create-run client project-id (:id instance) run run-steps))))

(defn find-sf-testset [client project-id testset-name]
  (ll-find-testset client project-id testset-name))

(defn create-or-update-sf-testset [client project-id author-id additional-test-fields sf-name additional-testset-fields sf-test-suites]
  ;; find the testset
  (if-let [testset (find-sf-testset client project-id sf-name)]
    (let [instances (ll-testset-instances client project-id (:id testset))
          tests     (map (fn [test-suite]
                           (let [name (str (:package-name test-suite) ":" (:name test-suite))]
                             [name test-suite (ll-find-test client project-id name)]))
                         sf-test-suites)
          nil-tests (filter #(nil? (last %)) tests)]
      (when (seq nil-tests)
        ;; create missing tests add add them to the testset
        (let [new-tests (map (partial create-sf-test client project-id author-id additional-test-fields)
                             (map second nil-tests))]
          (doseq [t new-tests]
            (ll-create-instance client project-id (:id testset) (:id t)))))
      ;; add any missing instances to the testset
      (doseq [test-id (difference (set (map #(read-string (:id (last %))) (remove #(nil? (last %)) tests)))
                                  (set (map #(get-in % [:attributes :test-id]) instances)))]
        (ll-create-instance client project-id (:id testset) test-id))
      testset)
    (create-sf-testset client project-id author-id additional-test-fields sf-name additional-testset-fields sf-test-suites)))
