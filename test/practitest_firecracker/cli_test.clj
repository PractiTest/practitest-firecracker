(ns practitest-firecracker.cli-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.cli :refer :all]
            [clojure.string :as string]
            [fixtures.practitest-firecracker.practitest-test :refer :all]))

(deftest test-parse-args
  (testing "Unsupported action"
    (let
      [res (second
             (string/split
               (:exit-message
                 (parse-args
                   '("asdasd"))) #"\n"))]
      (is (= res "Unsupported action [asdasd]"))))
  (testing "display-config action"
    (let
      [res (parse-args
             '("display-config"))]
      (is (= res display-config-settings))))
  (testing "display-options action"
    (let
      [res (parse-args
             '("display-options"))]
      (is (= res display-options-settings))))
  (testing "version action"
    (let
      [res (parse-args
             '("version"))]
      (is (= res version-settings)))))
