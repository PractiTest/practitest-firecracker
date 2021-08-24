(ns practitest-firecracker.practitest-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [practitest-firecracker.practitest :as SUT]))

(defn prepare-mocks [f]
  (with-redefs [SUT/backoff-timeout 1
                SUT/max-attempts 1]
    (with-fake-routes-in-isolation
      {"https://fake-api.local/api/v2/projects/1234567891/tests/bulk_search.json"
       {:post (fn [req]
                (println (pr-str req))
                {:status  429 
                 :headers {"Content-Type" "application/json"}
                 :body    "[]"})}}
      (f))))

(use-fixtures :each #'prepare-mocks)

(deftest ll-find-tests
  (let [client (SUT/make-client {:email        "foo@example.com"
                                 :api-token    "whatever"
                                 :api-uri      "https://fake-api.local"
                                 :max-api-rate 1})
        ress (SUT/ll-find-tests client [1234567891 true] ["one" "two" "three"])]
    (is (empty? ress))))
