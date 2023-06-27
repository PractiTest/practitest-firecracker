(ns practitest-firecracker.core-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.core :refer :all]))

(deftest test--main
  (testing "test -main"
    (=
      (-main
        {:name        "step name"
         :description "step description"})
      "")))
