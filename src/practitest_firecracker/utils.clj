(ns practitest-firecracker.utils
  (:require
   [clj-time.core                      :as t]
   [clojure.pprint                     :as pprint]))

(defn print-run-time [text start-time]
  (let [sec-pass   (t/in-seconds (t/interval start-time (t/now)))
        secs       (mod 60 (t/in-seconds (t/interval start-time (t/now))))
        minutes    (if (> sec-pass 60) (mod 60 (t/in-minutes (t/interval start-time (t/now)))) 0)
        hours      (if (> sec-pass 3600) (t/in-hours (t/interval start-time (t/now))) 0)]
    (pprint/pprint (format text hours minutes secs))))

(defn parse-id [id]
  (if (string? id)
    (Long/parseLong id)
    id))

(defn test-need-update? [test-suite test]
  (let [
        ;; log (pprint/pprint {:test-suite test-suite
                            ;; :test test})
        ]
    true))

(defn exit [status msg]
  (println msg)
  (System/exit status))