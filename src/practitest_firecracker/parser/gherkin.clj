(ns practitest-firecracker.parser.gherkin
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [lambdaisland.cucumber.gherkin :as gherkin]
            [lambdaisland.cucumber.jvm :as jvm])
  (:import (cucumber.runtime.io Resource)
           (java.io BufferedReader ByteArrayInputStream File FileInputStream InputStream StringBufferInputStream StringReader)
           (java.nio.charset Charset StandardCharsets)))

(defn- glue-example-arguments
  "Turns example table into vector of maps - args associated with values"
  [example]
  (map
    (fn [table-header table-row idx]
      ;; We need both map for lookups
      {:map (zipmap table-header table-row)
       ;; And ordered row (due to the way firecracker params are implemented)
       :row table-row
       ;; And we need index as well (0-based here)
       :index idx})
    (repeat (:table-header example))
    (:table-body example)
    (range)))

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
        all-params (mapcat glue-example-arguments examples)]
    (mapcat
      (fn [param]
        ;; Store params and values here for future reference
        (let [result (assoc scenario :outline-params param)
              expanded-name (expand-arguments (:name scenario) (:map param))]
          ;; We have couple of options out there
          [
           ;; 1 - simple substitution
           ;; e.g:
           [[(:name feature) expanded-name (:map param)] result]

           ;; 2 - glued as this <bare name> #1.<index>: <subst name>
           ;; e.g: BAS.3.02.06 - Configure a Quote with Docebo Elevate Bundle - NB &lt;Opportunity Name&gt; - #1.1: BAS.3.02.06 - Configure a Quote with Docebo Elevate Bundle - NB $AT-NB-Dis-GT30-ARR-LTE75-Elevate
           [[(:name feature)
             ;; Special case - if expanded is the same - then it's slightly different
             (if (= (:name scenario) expanded-name)
               (str (:name scenario) " - #1." (inc (:index param)))
               (str (:name scenario) " - #1." (inc (:index param)) ": " expanded-name))
             (:map param)] result]]))
         all-params)))

(defn- examples-section-end? [line]
  (str/starts-with? (str/lower-case (str/trim line)) "scenario:"))

;; TODO: tags support ??
(defn- extract-scenario-source
  [feature-root scenario]
  (let [feature-source (line-seq (BufferedReader. (StringReader. (:source feature-root))))

        outline? (= :gherkin/scenario-outline (:type scenario))

        start-line (:line (:location scenario))
        end-line (cond
                   ;; Special case for empty steps scenario
                   (empty? (:steps scenario))
                   start-line

                   outline?
                   ;; For outline need to extract examples as well
                   (let [start-examples (:line (:location (first (:examples scenario))))]
                     ;; Find end of examples sections - either end of file or new BDD scenario
                     (loop [i start-examples
                            coll (seq (drop start-examples feature-source))]
                       (if coll
                         (let [line (first coll)]
                           (if (examples-section-end? line)
                             i
                             (recur (inc i) (next coll))))
                         i)))

                   :else
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
  ["feature name" "scenario name" {"param" "value"}]

  ;; Scenario outlines get expanded so they will end with multiple keys
  ["feature name" "scenario outline <arg>" {}]
  ["feature name" "scenario outline \"val1\"" {"arg" "val1"}]
  ["feature name" "scenario outline \"val2\"" {"arg" "val2"}]

  ["feature name" "scenario outline no substitutions" {"arg" "val1"}]
  ["feature name" "scenario outline no substitutions" {"arg" "val2"}]
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
                            ;; We always will have feature + scenario key + nil (no params)
                            head [[feature-name (:name scenario) nil] scenario]]
                        ;; For outlines - expand scenarios and link them to original one
                        (if (= :gherkin/scenario-outline (:type scenario))
                          (cons head (expand-scenario-outline scenario feature))
                          [head]))))
            (:children feature)))))

(defn- strip-zwnbsp
  "Provided example specflow + playwright feature file have
  ZWNBSP character at the start of file which breaks gherkin parser.

  We're removing it here if it's present"
  [file]
  (let [data (slurp (io/file file))]
    (str/replace data "\uFEFF" "")))

;; Override file contents with string
(defn- string-from-file-resource ^Resource [^File file ^String data]
  (reify
    Resource
    (getPath ^String [_]
      (.getPath file))
    (getInputStream ^InputStream [_]
      (ByteArrayInputStream. (.getBytes data StandardCharsets/UTF_8)))

    Object
    (toString [_]
      (str "<string-from-file-resource: file=" file ">"))))

(defn- parse-gherkin-feature
  [file]
  (log/debug "Parsing gherkin feature file" file)
  (try
    (->> file
         strip-zwnbsp
         (string-from-file-resource (io/file file))
         jvm/parse-resource
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
        (filter (fn [^java.io.File file]
                  (and (not (.isDirectory file))
                       (str/ends-with? (str/lower-case (.getName file)) ".feature")))
                (file-seq (io/file dir)))))
    {}
    directories))

(comment
  (parse-gherkin-feature (io/resource "gherkin/identity.feature"))
  (parse-gherkin-feature (io/resource "gherkin/hello.feature"))
  (parse-gherkin-feature (io/resource "gherkin/datatable.feature")))
