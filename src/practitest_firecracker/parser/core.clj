(ns practitest-firecracker.parser.core
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.xml :as xml]
    [clojure.zip :as zip])
  (:import (java.text NumberFormat)
           (java.io ByteArrayInputStream)))

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

(defn zip-str [s]
  (zip/xml-zip
    (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))

(defn filter-tags [tag-key xml-content]
  (let [filter-result (filter #(= (:tag %) tag-key) xml-content)]
    filter-result))

(defn get-attrs [obj]
  (conj obj (:attrs obj)))

(defn case-data [case]
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

(defn get-case-info [test-cases]
  (map case-data test-cases))

(defn group-by-classname [val]
  (last (str/split (:classname (:attrs val)) #"\.")))

(defn group-by-name [val]
  (last (str/split (:name (:attrs val)) #">")))

(defn group-testcase-data [data multi-test-cases]
  (let [grouping-func (if (contains? (:attrs (first data)) :classname)
                        group-by-classname
                        group-by-name)]
    (if multi-test-cases
      (->> data
           (group-by grouping-func)
           (map (fn [[k vals]] [k (into {:test-cases (get-case-info vals)} (first (map :attrs vals)))]))
           (into {}))
      (->> data
           (map-indexed (fn [index val] {index (into {:test-cases (list (case-data val))} (:attrs val))}))
           (into {})))))

(defn test-data [test]
  (let [package (if (:classname test) (clojure.string/join "." (drop-last (str/split (:classname test) #"\."))) (:name test))
        name    (if (:classname test) (last (str/split (:classname test) #"\.")) (last (str/split (:name test) #">")))]
    (assoc test
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

(defn get-test-aggregations [test val]
  (let [map-vals      (vals val)
        map-vals-with (map #(assoc % :suite-name (:name (:attrs test))) map-vals)
        result        (first (conj (or (:test-list test) []) (map test-data map-vals-with)))]
    result))

(defn get-another-lvl-data [multi-test-cases sample data]
  (let [content-data (if sample (keep-indexed #(if (> 20 %1) %2) (:content data)) (:content data))]
    (case (:tag data)
      :testsuite (assoc (get-attrs data) :test-list
                                         (get-test-aggregations data (group-testcase-data (filter-tags :testcase content-data) multi-test-cases)))
      :testsuites (assoc data :testsuite-list
                              (for [suite content-data]
                                (get-another-lvl-data multi-test-cases sample suite)))
      nil)))

(defn clean-content [testsuite]
  (let [val (map (fn [suite]
                   (assoc suite :content nil)) (:testsuite-list testsuite))]
    {:testsuite-list (filter-tags :testsuite val)}))

(defn get-data [parsed-file multi-test-cases sample]
  (let [first-parsed-file (first parsed-file)]
    (if (= (:tag first-parsed-file) :testsuites)
      (->> first-parsed-file
           (get-another-lvl-data multi-test-cases sample)
           clean-content
           (into {}))
      (->> {:tag :testsuites :content parsed-file}
           (get-another-lvl-data multi-test-cases sample)
           clean-content
           (into {})))))

(defn testset-vals [gf]
  (let [val    (:testsuite-list gf)
        result (reduce conj () val)]
    result))

(defn get-files-data [parsed-files multi-test-cases sample]
  (let [grouped-files  (for [file parsed-files] (get-data file multi-test-cases sample))
        grouped        grouped-files
        testsuite-list (flatten (map testset-vals grouped))
        ]
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

(defn get-files-path [directory]
  (let [file-seqs      (.listFiles (io/file directory))
        filtered-files (filter (fn [file] (str/ends-with? (.getAbsolutePath file) ".xml")) file-seqs)
        filtered-paths (for [file filtered-files] (.getAbsolutePath file))]
    filtered-paths))

(defn parse-files [directory]
  (let [filtered-paths (get-files-path directory)
        files          (for [path filtered-paths] (slurp path))
        parsed-files   (for [file files] (zip-str file))]
    parsed-files))

(defn merge-results [parsed-files multi-test-cases multi-testsets testset-name sample]
  (let [grouped-data (get-files-data parsed-files multi-test-cases sample)
        result       (if multi-testsets (merge-testsets-by-name grouped-data) (merge-testsets grouped-data testset-name))
        ]
    result))

(defn send-directory [parsed-dirs multi-test-cases multi-testsets testset-name sample]
  (let [result (first
                 (conj (list)
                       (first
                         (for [dir parsed-dirs]
                           (merge-results dir multi-test-cases multi-testsets testset-name sample)))))]
    result))

(defn get-dir-by-path [path multi-test-cases multi-testsets testset-name sample]
  (let [directory   (clojure.java.io/file path)
        parsed-dirs (for [dir (file-seq directory)] (parse-files dir))]
    (send-directory parsed-dirs multi-test-cases multi-testsets testset-name sample)))

(defn -main [& [arg multi-test-cases multi-testsets testset-name sample]]
  (if-not (empty? arg)
    (let [result (get-dir-by-path arg (= "true" multi-test-cases) (= "true" multi-testsets) testset-name (= "true" sample))]
      result)
    (throw (Exception. "Must have at least one argument!"))))
