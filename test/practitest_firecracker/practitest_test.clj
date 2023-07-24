(ns practitest-firecracker.practitest-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.practitest :refer :all]))

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