(ns practitest-firecracker.parser.core
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.xml :as xml]
    [clojure.zip :as zip])
  (:import
    (java.io ByteArrayInputStream)
    (java.text NumberFormat)))

(defn round
  [x & {p :precision}]
  (if (< x 0.006)
    (if p
      (let [scale (Math/pow 10 p)]
        (-> x (* scale) Math/round (/ scale)))
      (Math/round x))
    x))

(defn str-to-number [val]
  (if (not (nil? val)) (.parse (NumberFormat/getInstance) val) 0))

(defn has-nested-testsuite? [element]
  (some #(= :testsuite (:tag %)) (:content element)))

(defn flatten-testsuite 
  "xUnit sometimes has hierarchy of `testsuite` tags.
   This function will flatten the hierarchy, capturing the parent testsuite names.
   For example, if there is a testsuite A which has a child B, which has testcases,
   we will leave only B, but change the name attribute to 'A: B'."
  [element]
  (loop [acc [] children (:content element) prefix (-> element :attrs :name)]
    (if (empty? children)
      acc
      (let [head (first children)
            tail (rest children)]
        (if (= :testsuite (:tag head))
          (let [head (update-in head [:attrs :name] #(str prefix ": " %))]
            (if (has-nested-testsuite? head)
              (recur (concat acc (flatten-testsuite head)) tail prefix)
              (recur (conj acc head) tail prefix)))
          (recur (conj acc head) tail prefix))))))

(defn preprocess-xunit 
  "Support for xUnit.
   Since xUnit does not have root testsuites tag, but does have hierarchy of testsuite tags (sometimes),
   Here we will wrap it in a fake testsuites tag.
   This is done to support the case when `flatten-testsuite` returns a list of the testsuites."
  [root]
  (if (and (= :testsuite (:tag root))
           (has-nested-testsuite? root))
    {:tag :testsuites :content (flatten-testsuite root)}
    root))

(defn normalize-attribute [value]
  (when value
    (str/replace value "/" ".")))

(defn normalize-attributes
  "Sometimes `classname` and `name` contain '/', we need to replace it with '.'"
  [element]
  (if (map? element)
    (if-let [_attrs (:attrs element)]
      (-> element
          (update-in [:attrs :name] normalize-attribute)
          (update-in [:attrs :classname] normalize-attribute)
          (update :content #(map normalize-attributes %)))
      (update element :content #(map normalize-attributes %)))
    element))

(defn zip-str [s]
  (zip/xml-zip
    (normalize-attributes
      (preprocess-xunit 
        (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))))

(defn filter-tags [tag-key xml-content]
  (let [filter-result (filter #(= (:tag %) tag-key) xml-content)]
    filter-result))

(defn get-attrs [obj]
  (conj obj (:attrs obj)))

(def valid-start-item #{"Given" "When" "And" "Then"})

(defn- first-word [s]
  (when s
    (first (str/split s #"\s"))))

(defn- convert-bdd-status [x]
  (case x
    "passed"
    ;; Yep, nil is passed status
    nil

    "failed"
    :failure

    "skipped"
    :skipped

    ;; else
    x))

;;
;; Some assumptions on BDD output parsing
;; 1. Every line is a separate step
;; 2. Valid step line should start with valid keyword
;; 3. We try to extract status of step using some heuristics (different for behave and cucumber)
;;
;; Examples:
;;
;; Given I am logged in as Admin in Salesforce.................................passed
;; And I delete all emails received by "automation.bas+new@openetlearning.net" in Gmail.passed
;;
(defn try-parse-cucumber-line
  [line]
  (let [status (second (re-find #"\.(passed|failed|skipped)$" line))
        word (first-word line)
        description (-> line
                        (str/split #"\.(passed|failed|skipped)$")
                        (first)
                        ;; Trim end-of-line dots
                        (str/replace #"\.+$" ""))]
    (when status
      {:status (convert-bdd-status status)
       :time 0
       :test-case-name word
       :bdd-line line
       :pt-test-step-name word
       :description description
       :full-name description})))

;;
;; Examples
;;
;; Given "Known" "Malware" has been set to "Block - Error Page" ... passed in 1.018s
;; And an end user requests a "Known" DNS Exfiltration URL ... failed in 0.626s
;;
(defn try-parse-behave-line
  [line]
  (let [[match status time] (re-find #"\.\.\. (passed|failed|skipped) in ([\d\.]+)s$" line)
        word (first-word line)
        description (-> line
                        (str/split #"\.\.\. (passed|failed|skipped) in ([\d\.]+)s$")
                        (first)
                        (str/trim))]
    (when status
      {:status (convert-bdd-status status)
       :time (str-to-number time)
       :test-case-name word
       :bdd-line line
       :pt-test-step-name word
       :full-name description
       :description description})))

(defn parse-bdd-output [case system-out]
  (->> (for [line (str/split-lines system-out)
             :let [trimmed (str/trim line)]
             :when (some #(str/starts-with? trimmed %) valid-start-item)]
         ;; try to parse both as behave and cucumber (they are mutually exclusive)
         (or (try-parse-behave-line trimmed)
             (try-parse-cucumber-line trimmed)))
       (remove nil?)
       (map (fn [{:keys [status] :as bdd-test-case}]
              ;; Merge other data (required for further processing)
              (dissoc (merge
                       case
                       bdd-test-case
                       {:failure-type status
                        :has-failure? (some? status)})
                      :system-message)))))

(defn- case-data [case]
  (let [content        (:content case)
        case-type      (filter #(contains? #{:error :failure :flake :skipped} (:tag %)) content)
        system-message (filter #(contains? #{:system-out :system-err} (:tag %)) content)
        attrs          (:attrs case)
        name           (if (:classname attrs) (:name attrs) (last (str/split (:name attrs) #">")))]
    (assoc attrs
      :time (str-to-number (:time attrs))
      :full-class-name (:classname attrs)
      :full-name (str (:classname attrs) "." (:name attrs))
      :has-failure? (some? case-type)
      :failure-message (if case-type (get-in (first case-type) [:attrs :message]) nil)
      :failure-detail (if system-message (str (first (:content (first system-message))) (first (:content (first case-type)))) nil)
      :class-name (when (:classname attrs) (last (str/split (:classname attrs) #"\.")))
      :failure-type (if (and case-type (first case-type)) (:tag (first case-type)) nil)
      ;; :case-type         case-type
      ;; :case-content      content
      ;; :name              name
      :test-case-name name
      :pt-test-step-name name
      :system-message system-message)))

(defn group-by-classname [val]
  (last (str/split (:classname (:attrs val)) #"\.")))

(defn group-by-name [val]
  (last (str/split (:name (:attrs val)) #">")))

(defn group-testcase-data [data {:keys [test-case-as-pt-test-step
                                        detect-bdd-steps]
                                 :as options}]
  (let [grouping-func (if (contains? (:attrs (first data)) :classname)
                        group-by-classname
                        group-by-name)]
    (if test-case-as-pt-test-step
      (->> data
           (group-by grouping-func)
           (map
             (fn [[k vals]]
               [k (into {:test-cases (map case-data vals)} (first (map :attrs vals)))]))
           (into {}))
      (->> data
           (map-indexed
             (fn [index val]
               {index (into
                        {:test-cases (let [case (case-data val)]
                                       ;; Expand data for steps if BDD detection is enabled
                                       (if (and detect-bdd-steps
                                                (:system-message case))
                                         (or (seq (parse-bdd-output case (first (:content (first (:system-message case))))))
                                             ;; Fallback to usual case
                                             (list case))
                                         (list case)))}
                        (:attrs val))}))
           (into {})))))

(defn test-data [scenarios-map test]
  (let [package (if (:classname test) (clojure.string/join "." (drop-last (str/split (:classname test) #"\."))) (:name test))
        name (if (:classname test) (last (str/split (:classname test) #"\.")) (last (str/split (:name test) #">")))

        ;; Lookup BDD scenario
        gherkin-scenario (get scenarios-map [(:classname test) (:name test)])
        ]
    (when (some? gherkin-scenario)
      (log/debug "Detected gherkin scenario" (:name gherkin-scenario) "for test" (:classname test) (:name test)))

    (assoc test
      :bdd-test? (some? gherkin-scenario)
      :gherkin-scenario gherkin-scenario
      ;; Extract scenario outline params to special arg (accessible via ?outline-params-row in firecracker config)
      :outline-params-row (:row (:outline-params gherkin-scenario))
      :errors (count (filter #(= (:failure-type %) :error) (:test-cases test)))
      :failures (count (filter #(= (:failure-type %) :failure) (:test-cases test)))
      :flakes (count (filter #(= (:failure-type %) :flake) (:test-cases test)))
      :skipped (count (filter #(= (:failure-type %) :skipped) (:test-cases test)))
      :tests (count (:test-cases test))
      ;; :name                  name
      ;; :name-test-suite       name
      :suite-name (:suite-name test)
      :pt-first-case-name (:name (first (:test-cases test)))
      :pt-test-name name
      :pt-name-combine (str name " - " (:name (first (:test-cases test))))
      :time-elapsed (round (reduce + (map :time (:test-cases test))) :precision 3)
      :full-class-name (:classname test)
      :package-name package)))

(defn get-test-aggregations [test val {:keys [scenarios-map]
                                       :as options}]
  (let [map-vals      (vals val)
        map-vals-with (map #(assoc % :suite-name (:name (:attrs test))) map-vals)
        result        (first (conj (or (:test-list test) []) (map (partial test-data scenarios-map) map-vals-with)))]
    result))

(defn get-another-lvl-data [{:keys [sample
                                    scenarios-map]
                             :as options}
                            data]
  (let [content-data (if sample (keep-indexed #(if (> 20 %1) %2) (:content data)) (:content data))]
    (case (:tag data)
      :testsuite (assoc (get-attrs data) :test-list
                                         (get-test-aggregations data
                                                                (group-testcase-data (filter-tags :testcase content-data) options)
                                                                options))
      :testsuites (assoc data :testsuite-list
                              (for [suite content-data]
                                (get-another-lvl-data options suite)))
      nil)))

(defn clean-content [testsuite]
  (let [val (map (fn [suite]
                   (assoc suite :content nil)) (:testsuite-list testsuite))]
    {:testsuite-list (filter-tags :testsuite val)}))

(defn get-data [parsed-file options]
  (let [first-parsed-file (first parsed-file)]
    (if (= (:tag first-parsed-file) :testsuites)
      (->> first-parsed-file
           (get-another-lvl-data options)
           clean-content
           (into {}))
      (->> {:tag :testsuites :content parsed-file}
           (get-another-lvl-data options)
           clean-content
           (into {})))))

(defn testset-vals [gf]
  (let [val    (:testsuite-list gf)
        result (reduce conj () val)]
    result))

(defn get-files-data [parsed-files options]
  (let [grouped-files  (for [file parsed-files] (get-data file options))
        grouped        grouped-files
        testsuite-list (flatten (map testset-vals grouped))]
    testsuite-list))

(defn merged-testset [testsets tests testset-name]
  (list {:tag       :testsuite
         :test-list tests
         :name      testset-name
         :tests     (reduce + (map #(str-to-number %) (map :tests testsets)))
         :time      (reduce + (map #(str-to-number %) (map :time testsets)))
         :failures  (reduce + (map #(str-to-number %) (map :failues testsets)))
         :skipped   (reduce + (map #(str-to-number %) (map :skipped testsets)))
         }))

(defn merge-testsets [testsets testset-name]
  (let [group-tests (flatten (reduce conj (for [testset testsets] (:test-list testset))))]
    (merged-testset testsets group-tests testset-name)))

(defn merge-testsets-by-name [testsets]
  (let [testsets-by-name (group-by :name testsets)
        merged-testsets  (for [sub-testsets testsets-by-name]
                           (reduce conj (merge-testsets (last sub-testsets) (first sub-testsets))))]
    merged-testsets))

(defn parse-n-merge-data [grouped-files-map parsed-content]
  (let [merge-content (merge (get grouped-files-map (:test-list parsed-content)) parsed-content)]
    merge-content))

(defn get-files-path [directory extension]
  (let [file-seqs      (.listFiles (io/file directory))
        filtered-files (filter (fn [file] (str/ends-with? (.getAbsolutePath file) extension)) file-seqs)
        filtered-paths (for [file filtered-files] (.getAbsolutePath file))]
    filtered-paths))

(defn parse-files [directory]
  (let [filtered-paths (get-files-path directory ".xml")
        files          (for [path filtered-paths] (slurp path))
        parsed-files   (for [file files] (zip-str file))]
    parsed-files))

(defn merge-results [parsed-files {:keys [multitestset
                                          testset-name]
                                   :as options}]
  (let [grouped-data (get-files-data parsed-files options)]
    (if multitestset
      (merge-testsets-by-name grouped-data)
      (merge-testsets grouped-data testset-name))))

(defn send-directory [parsed-dirs options]
  (first
    (conj (list)
          (first
            (for [dir parsed-dirs]
              (merge-results dir options))))))

(defn get-dir-by-path [path options]
  (let [directory   (clojure.java.io/file path)
        parsed-dirs (for [dir (file-seq directory)] (parse-files dir))]
    (send-directory parsed-dirs options)))

(defn -main [& [arg multi-test-cases multi-testsets testset-name sample]]
  (if-not (empty? arg)
    (let [result (get-dir-by-path arg
                                  {:test-case-as-pt-test-step (= "true" multi-test-cases)
                                   :multitestset (= "true" multi-testsets)
                                   :testset-name testset-name
                                   :sample (= "true" sample)})]
      result)
    (throw (Exception. "Must have at least one argument!"))))

(comment
  (let [s (slurp "test-data/robot/RobotDemo/xunit-combinded.xml")]
    (zip/xml-zip
      (preprocess-xunit
        (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8"))))))

  (let [s (slurp "test-data/carmit/junit-report.xml")]
    (zip/xml-zip
      (preprocess-xunit
        (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8"))))))

  (let [s (slurp "test-data/pb/report.xml")]
    (normalize-attributes
      (preprocess-xunit
        (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8"))))))

  )
