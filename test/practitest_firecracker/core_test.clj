(ns practitest-firecracker.core-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.core :refer :all]))

(deftest test--main
  (testing "test -main"
    (=
      (-main
        "--api-uri=http://localhost:3000" "--reports-path=XML_R" "--author-id=1" "--config-path=FC_config_3_5_2023-14_10.json" "--max-api-rate=4000" "--display-action-logs=true" "create-and-populate-testset")
      "")))
