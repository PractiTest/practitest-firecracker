(ns practitest-firecracker.eval-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.eval :as eval]
            [practitest-firecracker.api :as api]
            [practitest-firecracker.cli :refer [parse-additional-fields]]
            [cheshire.core :as json]))

(def ^:private testset-fields
  (parse-additional-fields
    (json/generate-string
      {:custom-fields {"---f-345678" "?hostname"
                       "---f-66666"  "Salesforce"
                       "---f-77777"  "Salesforce Inc"}})))

(deftest create-sf-testset-resolves-dynamic-and-constant-fields
  (testing "create-sf-testset resolves query-DSL against the representative suite"
    (let [captured       (atom nil)
          options        {:project-id                123
                          :display-action-logs       false
                          :additional-testset-fields testset-fields}
          representative {:hostname "ci-runner-7"}]
      (with-redefs [eval/ensure-custom-field-values (fn [& _] nil)
                    api/ll-create-testset           (fn [_client _pid attributes _test-ids]
                                                      (reset! captured attributes)
                                                      {:id "999"})]
        (eval/create-sf-testset nil options [] "My TestSet" representative))
      (is (= "ci-runner-7" (get-in @captured [:custom-fields (keyword "---f-345678")]))
          "dynamic ?hostname must resolve to the suite value")
      (is (= "Salesforce" (get-in @captured [:custom-fields (keyword "---f-66666")]))
          "constant value must pass through unchanged")
      (is (= "Salesforce Inc" (get-in @captured [:custom-fields (keyword "---f-77777")]))
          "multi-word constant value must not be truncated to its first word"))))

(deftest update-sf-testset-resolves-dynamic-and-constant-fields
  (testing "update-sf-testset resolves query-DSL against the representative suite"
    (let [captured       (atom nil)
          options        {:project-id                123
                          :display-action-logs       false
                          :additional-testset-fields testset-fields}
          representative {:hostname "ci-runner-9"}]
      (with-redefs [eval/ensure-custom-field-values (fn [& _] nil)
                    api/ll-update-testset           (fn [_client _pdisplay attributes _steps _cf-id]
                                                      (reset! captured attributes)
                                                      {:id "999"})]
        (eval/update-sf-testset nil options "My TestSet" {} representative 999))
      (is (= "ci-runner-9" (get-in @captured [:custom-fields (keyword "---f-345678")]))
          "dynamic ?hostname must resolve to the suite value")
      (is (= "Salesforce" (get-in @captured [:custom-fields (keyword "---f-66666")]))
          "constant value must pass through unchanged")
      (is (= "Salesforce Inc" (get-in @captured [:custom-fields (keyword "---f-77777")]))
          "multi-word constant value must not be truncated to its first word"))))
