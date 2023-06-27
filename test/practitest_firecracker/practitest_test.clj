(ns practitest-firecracker.practitest-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.practitest :refer :all]))

(deftest test-translate-step-attributes
  (testing "test translate-step-attributes"
    (=
      (translate-step-attributes
        {:name        "step name"
         :description "step description"})
      {:pt-test-step-name "step name"
       :description       "step description"})))

(deftest test-translate-step-attributes
  (testing "test translate-step-attributes without description"
    (=
      (translate-step-attributes
        {:name        "step name"})
      {:pt-test-step-name "step name"
       :description       nil})))