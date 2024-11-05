(ns practitest-firecracker.utils
  (:require
    [clj-time.core :as t]
    [clojure.pprint :as pprint]
    [clojure.string :as string]
    [cheshire.core :as json]))

(defn print-run-time [text start-time]
  (let [sec-pass (t/in-seconds (t/interval start-time (t/now)))
        secs (mod 60 (t/in-seconds (t/interval start-time (t/now))))
        minutes (if (> sec-pass 60) (mod 60 (t/in-minutes (t/interval start-time (t/now)))) 0)
        hours (if (> sec-pass 3600) (t/in-hours (t/interval start-time (t/now))) 0)]
    (pprint/pprint (format text hours minutes secs))))

(defn test-need-update? [test-suite test]
  (let [
        ;; log (pprint/pprint {:test-suite test-suite
                            ;; :test test})
        ]
    true))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn group-errors [body]
  (str "Errors: \n" (string/join "\n" (map #(str "- " %) (map :title (:errors (json/parse-string body true)))))))

(defn pformat [& args]
  (with-out-str
    (apply pprint/pprint args)))

(defn replace-map
  [string values-map]
  (when (and string (not (empty? (filter #(not (nil? (first %))) values-map))))
    (string/replace string
                    (re-pattern
                      (apply str
                             (interpose "|"
                                        (map #(java.util.regex.Pattern/quote %)
                                             (keys values-map)))))
                    values-map)))

(defn replace-keys [params]
  (when params
    (into {} (map (fn [[x y]] {(when x (str "<" (if (keyword? x) (name x) x) ">")) y}) params))))

(defn transform-keys
  [m f & args]
  (when m
    (reduce-kv
      (fn [a k v]
        (assoc a (apply f k args) v))
      {}
      m)))
