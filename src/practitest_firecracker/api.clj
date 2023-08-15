(ns practitest-firecracker.api
  (:require
    [clj-http.client :as http]
    [clojure.string :as string]
    [throttler.core :refer [fn-throttler]]
    [clojure.tools.logging :as log]
    [practitest-firecracker.const :refer :all]
    [practitest-firecracker.utils :refer [exit group-errors pformat]]))

(def backoff-timeout "Backoff timeout in seconds" 20)
(def max-attempts "Number of attempts to run" 10)
(def timeout-between-attempts "Timeout in seconds between attempts" 1)

(defn create-api-throttler [rate]
  (let [fn-th (fn-throttler rate :minute)]
    fn-th))

(defn build-uri [base-uri resource-uri-template & params]
  (apply format (str base-uri resource-uri-template) params))

(defn throw-api-exception [ex-info status body uri]
  (exit status (group-errors body)))

(defn api-call [{:keys [credentials uri method query-params form-params]}]
  (assert (not (and query-params form-params))
          "both `query-params` and `form-params` can't be specified")
  (loop [results []
         uri uri
         attempts max-attempts
         params (cond-> {:basic-auth       credentials
                         :throw-exceptions false
                         :as               :json}
                        query-params (assoc :query-params (conj query-params {:source "firecracker" :firecracker-version fc-version}))
                        form-params (assoc :form-params (conj form-params {:source "firecracker" :firecracker-version fc-version}) :content-type :json))]
    (if (nil? uri)
      results
      (let [{:keys [status body]} (method uri params)]
        (if (> attempts 0)
          (case status
            504 (do
                  ;; load balancer freaking out, lets try again
                  (Thread/sleep (* timeout-between-attempts 1000))
                  (recur results uri (dec attempts) params))
            (500 502 503) (do
                            (log/warnf "%s responded with %s - %s more attempts" uri status attempts)
                            (Thread/sleep (* timeout-between-attempts 1000))
                            (recur results uri (dec attempts) params))
            429 (do
                  (log/warnf "API rate limit reached, waiting for %s seconds" backoff-timeout)
                  (Thread/sleep (* backoff-timeout 1000))
                  (recur results uri (dec attempts) params))
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

(defn make-client [{:keys [email api-token api-uri max-api-rate]}]
  {:credentials            [email api-token]
   :base-uri               (str api-uri
                                (if (string/ends-with? api-uri "/") "" "/")
                                "api/v2")
   :max-api-rate-throttler (create-api-throttler max-api-rate)})

(defn ll-testset-instances [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] testset-id test-ids]
  (when display-action-logs (log/infof "get instances from testsets %s" testset-id))
  (let [uri (build-uri base-uri testset-instances-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       (max-api-rate-throttler http/get)
               :query-params (cond-> {:set-ids testset-id}
                                     test-ids (assoc :test-ids test-ids))})))

(defn ll-test-steps [{:keys [base-uri credentials max-api-rate-throttler]} project-id test-id]
  (let [uri (build-uri base-uri test-steps-uri project-id)]
    (api-call {:credentials  credentials
               :uri          uri
               :method       (max-api-rate-throttler http/get)
               :query-params {:test-ids test-id}})))

(defn ll-create-test [{:keys [base-uri credentials max-api-rate-throttler]} project-id attributes steps]
  (let [uri (build-uri base-uri create-test-uri project-id)]
    (first
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/post)
                 :form-params {:data {:type       "tests"
                                      :attributes attributes
                                      :steps      {:data steps}}}}))))

(defn ll-create-testset [{:keys [base-uri credentials max-api-rate-throttler]} project-id attributes test-ids]
  (let [uri (build-uri base-uri create-testset-uri project-id)]
    (first
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/post)
                 :form-params {:data {:type       "sets"
                                      :attributes attributes
                                      :instances  {:test-ids test-ids}}}}))))

(defn make-instances [testset-tests testname-to-params test-id-testname]
  (for [[testset-id test-ids-num] testset-tests
        test-id-num test-ids-num
        index (range (last test-id-num))]
      {:type       "instances"
       :attributes {:set-id     testset-id
                    :test-id    (first test-id-num)
                    :parameters (get (get testname-to-params (get test-id-testname (first test-id-num))) index)}}))

(defn has-duplicates? [key runs]
  (let [grouped (group-by key (into [] runs))]
    (not (= (count runs) (count grouped)))))

(defn ll-create-instances [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] instances]
  (when display-action-logs (log/infof "create instances"))
  (let [uri (build-uri base-uri create-instance-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      (max-api-rate-throttler http/post)
               :form-params {:data instances}})))

(defn ll-create-run [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] instance-id attributes steps]
  (when display-action-logs (log/infof "create run for instance %s" instance-id))
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (first
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/post)
                 :form-params {:data {:type       "instances"
                                      :attributes (merge attributes {:instance-id instance-id})
                                      :steps      {:data steps}}}}))))

(defn ll-create-runs [{:keys [base-uri credentials max-api-rate-throttler] :as client} [project-id display-action-logs] runs]
  (when display-action-logs (log/infof "create runs"))
  (let [uri (build-uri base-uri create-run-uri project-id)]
    (if (has-duplicates? :instance-id runs)
      (let [grouped (group-by :instance-id runs)
            duplicates (map first (vals (into {} (filter #(> (count (last %)) 1) grouped))))
            uniq (map first (vals (into {} (filter #(= (count (last %)) 1) grouped))))]
        (do (doall
              (for [run duplicates]
                (ll-create-run {:base-uri base-uri :credentials credentials :max-api-rate-throttler max-api-rate-throttler} [project-id display-action-logs] (:instance-id run) (:attributes run) (:steps run))))
            (ll-create-runs {:base-uri base-uri :credentials credentials :max-api-rate-throttler max-api-rate-throttler} [project-id display-action-logs] uniq)))
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/post)
                 :form-params {:data
                               (reduce conj []
                                       (doall (for [run (into () runs)]
                                                {:type       "instances"
                                                 :attributes (assoc (:attributes run) :instance-id (:instance-id run))
                                                 :steps      {:data (reduce conj [] (:steps run))}})))}}))))

(defn ll-find-tests [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] name-list]
  (when display-action-logs (log/infof "searching for testsets"))
  (let [uri (build-uri base-uri bulk-list-tests-uri project-id)]
    (api-call {:credentials credentials
               :uri         uri
               :method      (max-api-rate-throttler http/post)
               :form-params {:data name-list}})))

(defn ll-find-test [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] name]
  (when display-action-logs (log/infof "searching for test %s" name))
  (let [uri (build-uri base-uri list-tests-uri project-id)]
    ;; in case there are more than one test with this name, return the first one
    (first
      (api-call {:credentials  credentials
                 :uri          uri
                 :method       (max-api-rate-throttler http/get)
                 :query-params {:name_exact name}}))))

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
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/get)}))))

(defn ll-update-custom-field [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] cf-id possible-values]
  (when display-action-logs (log/infof "update custom field %s to possible-values: %s" cf-id possible-values))
  (let [uri (build-uri base-uri custom-field-uri project-id cf-id)]
    (first
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/put)
                 :form-params {:data {:type       "custom_field"
                                      :attributes {:possible-values possible-values}}}}))))

(defn ll-update-test [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] attributes steps cf-id]
  (when display-action-logs (log/infof "update test %s in custom field id %s" (:name attributes) cf-id))
  (let [uri (build-uri base-uri update-test-uri project-id cf-id)]
    (first
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/put)
                 :form-params {:data {:type       "tests"
                                      :attributes attributes
                                      :steps      {:data steps}}}}))))

(defn ll-update-testset [{:keys [base-uri credentials max-api-rate-throttler]} [project-id display-action-logs] attributes steps cf-id]
  (when display-action-logs (log/infof "update testset %s in field id %s" (:name attributes) cf-id))
  (let [uri (build-uri base-uri update-testset-uri project-id cf-id)]
    (first
      (api-call {:credentials credentials
                 :uri         uri
                 :method      (max-api-rate-throttler http/put)
                 :form-params {:data {:type       "sets"
                                      :attributes attributes
                                      :steps      {:data steps}}}}))))
