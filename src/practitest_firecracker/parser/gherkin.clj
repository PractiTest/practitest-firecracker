(ns practitest-firecracker.parser.gherkin
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [practitest-firecracker.parser.core :as pc]
            [lambdaisland.cucumber.gherkin :as gherkin]
            [lambdaisland.cucumber.jvm :as jvm])
  (:import (java.io BufferedReader StringReader)))

(defn- glue-example-arguments
  "Turns example table into vector of maps - args associated with values"
  [example]
  (map
    (fn [table-header table-row]
      ;; We need both map for lookups
      {:map (zipmap table-header table-row)
       ;; And ordered row (due to the way firecracker params are implemented)
       :row table-row})
    (repeat (:table-header example))
    (:table-body example)))

(defn- expand-arguments
  "Substitutes <arg> with values from params-map"
  [string params-map]
  (reduce-kv
    (fn [result arg value]
      (str/replace result (str "<" arg ">") (str value)))
    string
    params-map))

;; Expand into multiple keys with args replaced
(defn- expand-scenario-outline
  [scenario feature]
  (let [examples (:examples scenario)
        all-params (glue-example-arguments (first examples))]
    (map (fn [param]
           [[(:name feature) (expand-arguments (:name scenario) (:map param))]
            ;; Store params and values here for future reference
            (assoc scenario :outline-params param)])
         all-params)))

;; TODO: tags support ??
(defn- extract-scenario-source
  [feature-root scenario]
  (let [feature-source (line-seq (BufferedReader. (StringReader. (:source feature-root))))

        outline? (= :gherkin/scenario-outline (:type scenario))

        start-line (:line (:location scenario))
        end-line (if outline?
                   ;; For outline need to extract examples as well
                   (let [start-examples (:line (:location (last (:examples scenario))))]
                     ;; Find end of examples sections - either end of file or empty line
                     (loop [i start-examples
                            coll (seq (drop start-examples feature-source))]
                       (if coll
                         (let [line (first coll)]
                           (if (str/blank? line)
                             i
                             (recur (inc i) (next coll))))
                         i)))
                   (:line (:location (last (:steps scenario)))))

        ;; Extract relevant scenario lines from source
        scenario-lines (take (inc (- end-line start-line))
                             (drop (dec start-line) feature-source))

        ;; Drop extra indentation
        start-column (dec (:column (:location scenario)))
        drop-chars (str/join (repeat start-column \space))]

    (->> scenario-lines
         ;; Strip extra indentation
         (map #(str/replace-first % drop-chars ""))
         ;; And glue together with newlines
         (str/join "\n"))))

(comment
  ;; Scenario lookup map is a map with keys that looks like
  ["feature name" "scenario name"]

  ;; Scenario outlines get expanded so they will end with multiple keys
  ["feature name" "scenario outline <arg>"]
  ["feature name" "scenario outline \"val1\""]
  ["feature name" "scenario outline \"val2\""]
  ;; etc - all pointing to the same scenario object
  )

(defn- build-scenarios-lookup-map
  [feature-root]
  (when feature-root
    (let [feature (get-in feature-root [:document :feature])
          feature-name (:name feature)]
      (into {}
            (mapcat (fn [scenario]
                      (let [
                            ;; Extract scenario sources from feature file
                            scenario (assoc scenario :scenario-source
                                                     (extract-scenario-source feature-root scenario))
                            ;; We always will have feature + scenario key
                            head [[feature-name (:name scenario)] scenario]]
                        ;; For outlines - expand scenarios and link them to original one
                        (if (= :gherkin/scenario-outline (:type scenario))
                          (cons head (expand-scenario-outline scenario feature))
                          [head]))))
            (:children feature)))))

(defn- parse-gherkin-feature
  [file]
  (log/debug "Parsing gherkin feature file" file)
  (try
    (->> file
         jvm/parse
         gherkin/gherkin->edn)
    (catch Exception e
      (log/warn e "Error parsing feature file - scenario will not be used in detection"))))

(defn- merge-with-warnings
  [m1 m2]
  (reduce-kv
    (fn [acc k v]
      (when (contains? acc k)
        (println (format "WARN: overwriting scenario in lookup map (feature '%s', scenario '%s')" (first k) (second k))))
      (assoc acc k v))
    m1
    m2))

(defn parse-all-features
  "Returns lookup map of [<feature> <scenario>] => scenario definition from passed directories sequence"
  [directories]
  (reduce
    (fn [lookup-map dir]
      (log/debug "Parsing features directory" dir)

      (reduce
        (fn [lookup-map feature-file]
          (->> feature-file
               (parse-gherkin-feature)
               (build-scenarios-lookup-map)
               (merge-with-warnings lookup-map)))
        lookup-map
        (pc/get-files-path dir ".feature")))
    {}
    directories))

(comment
  (parse-gherkin-feature (io/resource "gherkin/identity.feature"))
  (parse-gherkin-feature (io/resource "gherkin/hello.feature"))
  (parse-gherkin-feature (io/resource "gherkin/datatable.feature")))
