(ns practitest-firecracker.api-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.api :as api]))

(deftest effective-api-rate-test
  (testing "clamps to the account limit when the configured rate is higher"
    (is (= 30 (api/effective-api-rate 300 30))))
  (testing "keeps the configured rate when it is at or below the account limit"
    (is (= 30 (api/effective-api-rate 30 300)))
    (is (= 30 (api/effective-api-rate 30 30))))
  (testing "falls back to the configured rate when the account limit is unknown"
    (is (= 300 (api/effective-api-rate 300 nil)))))

(deftest parse-account-rate-test
  (testing "reads calls-per-minute (api-max) from the account.json envelope"
    (is (= 30 (api/parse-account-rate {:data {:attributes {:api-max 30}}})))
    (is (= 120 (api/parse-account-rate {:data {:attributes {:api-max 120}}}))))
  (testing "returns nil when api-max is missing or invalid"
    (is (nil? (api/parse-account-rate {:data {:attributes {}}})))
    (is (nil? (api/parse-account-rate {})))
    (is (nil? (api/parse-account-rate {:data {:attributes {:api-max 0}}})))))
