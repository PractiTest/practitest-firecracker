(ns practitest-firecracker.parser.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [practitest-firecracker.parser.core :as parser]))

(defn- write-file! [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    f))

(deftest parse-files-skips-empty-and-unparseable
  (testing "empty, whitespace-only and malformed XML files are skipped, valid ones parsed"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "fc-parse-files-" (System/currentTimeMillis)))]
      (.mkdirs dir)
      (try
        (write-file! dir "empty.xml" "")
        (write-file! dir "blank.xml" "  \n\t ")
        (write-file! dir "broken.xml" "<testsuite><testcase>")
        (write-file! dir "good.xml"
                     "<testsuite name=\"s\"><testcase name=\"t\" classname=\"c\"/></testsuite>")
        (let [parsed (doall (parser/parse-files dir))]
          ;; Only the single valid file should survive
          (is (= 1 (count parsed)))
          (is (= :testsuite (:tag (first (first parsed))))))
        (finally
          (doseq [f (.listFiles dir)] (.delete f))
          (.delete dir))))))
