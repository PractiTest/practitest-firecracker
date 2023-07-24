(ns practitest-firecracker.utils-test
  (:require [clojure.test :refer :all]
            [clj-time.core                      :as t]
            [practitest-firecracker.utils :refer :all]))

(deftest test-group-errors
  (is (= "Errors: \n- ABC\n- DEF" (group-errors "{\"errors\": [{\"title\": \"ABC\"}, {\"title\": \"DEF\"}]}"))))