(ns practitest-firecracker.cli-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.cli :refer :all]))

(deftest test-parse-args
  (testing "test parse-args"
    (=
      (parse-args
        '("--api-uri=http://localhost:3000" "--reports-path=XML_R" "--author-id=1" "--max-api-rate=4000" "--display-action-logs=true" "help"))
      "ABC")))
