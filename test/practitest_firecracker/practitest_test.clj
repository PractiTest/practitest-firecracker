(ns practitest-firecracker.practitest-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.practitest :refer :all]
            [practitest-firecracker.api :as api]
            [practitest-firecracker.eval :as eval]))

(deftest create-testsets-representative-suite-uses-suite-attrs
  (testing "the representative suite passed downstream reflects the testsuite's own attrs, not a test-case's"
    (let [captured       (atom nil)
          sf-test-suites {:name        "MySuite"
                           :suite-attrs {:name "MySuite" :hostname "ci-box-42"}
                           :test-list   [{:name "testFoo" :classname "com.example.MyTest"}]}
          options        {:project-id 123 :display-action-logs false}]
      (with-redefs [api/ll-find-testset   (fn [& _] nil)
                    eval/create-sf-testset (fn [_client _options _suites _name representative]
                                              (reset! captured representative)
                                              {:id "1"})]
        (create-testsets nil options [sf-test-suites]))
      (is (= "ci-box-42" (:hostname @captured))
          "must resolve from the testsuite element's own attrs")
      (is (not= "testFoo" (:name @captured))
          "must not resolve from a test-case's attrs"))))

(deftest test-translate-step-attributes
  (testing "test translate-step-attributes"
    (is
      (=
        (translate-step-attributes
          {:name        "step name"
           :description "step description"})
        {:pt-test-step-name "step name"
         :description       "step description"}))))

(deftest test-translate-step-attributes
  (testing "test translate-step-attributes without description"
    (is
      (=
        (translate-step-attributes
          {:name "step name"})
        {:pt-test-step-name "step name"
         :description       nil}))))