(ns practitest-firecracker.cli
  (:require
   [clojure.tools.cli                :refer [parse-opts]]
   [clojure.java.io                  :refer [file reader]]
   [clojure.walk                     :refer [postwalk]]
   [clojure.string                   :as string]
   [cheshire.core                    :as json]
   [practitest-firecracker.query-dsl :refer [read-query]]))

(defn parse-additional-fields [v]
  (postwalk #(if (string? %) (read-query %) %)
            (json/parse-string v true)))

(def cli-options
  [[nil "--api-uri URI" "API URI" :default "https://api.practitest.com"]
   [nil "--api-token TOKEN" "API token"]
   [nil "--email EMAIL" "Your email address"]
   [nil "--reports-path PATH"
    "Path to surefire reports directory. Can be provided multiple times to specify multiple directories. You can also provide multiple directories separated by comma in the same --reports-path option"
    :parse-fn #(string/split % #"\s*,\s*")
    :validate [(fn [paths]
                 (every? #(.exists (file %)) paths))
               "Directory doesn't exist"]
    :assoc-fn (fn [m k v]
                (reduce #(update %1 k conj %2) m v))]
   [nil "--config-path PATH"
    "Path to firecracker configuration file"
    :validate [(fn [paths]
                 #(.exists (file %)) paths)
               "Config file doesn't exist"]]
   [nil "--project-id PROJECT-ID" "PractiTest Project ID"
    :parse-fn #(Integer/parseInt %)]
   [nil "--max-api-rate MAX-API-RATE" "API LIMIT in call per minute"
    :parse-fn #(Integer/parseInt %) :default 30]
   [nil "--testset-name TESTSET-NAME" "PractiTest TestSet name"]
   [nil "--testset-id TESTSET-ID" "PractiTest TestSet ID"
    :parse-fn #(Integer/parseInt %)]
   [nil "--author-id AUTHOR-ID" "PractiTest user ID"]
   [nil "--additional-test-fields JSON"
    "JSON containing the fields that should be added when creating PractiTest Test"
    :default {}
    :parse-fn parse-additional-fields]
   [nil "--additional-testset-fields JSON"
    "JSON containing the fields that should be added when creating PractiTest TestSet"
    :default {}
    :parse-fn parse-additional-fields]
   [nil "--test-case-as-pt-test" :default true]
   [nil "--test-case-as-pt-test-step" :default false]
   #_[nil "--pt-test-name DSL"
    :parse-fn read-query
    :default (read-query "(concat (join (capitalize (drop 2 (tokenize-package ?package-name)))) \": \" (join (capitalize (drop-last (tokenize-class-name ?test-suite-name)))))")]
   [nil "--pt-test-name DSL"
    :parse-fn read-query
    :default (read-query "?test-suite-name")]
   #_[nil "--pt-test-step-name DSL"
    :parse-fn read-query
    :default (read-query "(join (capitalize (tokenize-class-name ?test-case-name)))")]
   [nil "--pt-test-step-name DSL"
    :parse-fn read-query
    :default (read-query "?test-case-name")]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["PractiTest Surefire reports analyzer."
        ""
        "Usage: java -jar practitest-firecracker-standaline.jar [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  create-testset              Analyzes the given Surefire reports directory and creates a TestSet with Tests and Steps in PractiTest to reflect"
        "                              the structure of the report. Returns a TestSet ID that you should use to run the 'populate-testset' action"
        "  populate-testset            Analyzes the given Surefire reports directory and populates the given TestSet with the data from the report"
        "  create-and-populate-testset Shortcut to perform both actions. If the TestSet with the given name already exists, it will be reused. If it exists, but has completely different set of tests, an error will be reported."
        ""]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn missing-option-msg [action-name option-name]
  (format "%s is required for '%s' action" option-name action-name))

(defn parse-config-file [options]
  (let [parsed-json    (json/parse-stream (reader (:config-path options)) true)
        new-additional-test-fields  (parse-additional-fields (json/generate-string (:additional-test-fields parsed-json)))
        new-additional-testset-fields (parse-additional-fields (json/generate-string (:additional-testset-fields parsed-json)))
        new-parsed-json    (merge parsed-json {:additional-testset-fields new-additional-testset-fields
                                               :additional-test-fields new-additional-test-fields})]
    new-parsed-json))

(defn parse-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        new-parsed-json  (when (not (= (:config-path options) nil)) (parse-config-file options))
        options          (merge options new-parsed-json)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (= "display-config" (first arguments))
      (cond
        :else
        {:action "display-config" :options options})

      (= "display-options" (first arguments))
      (cond
        :else
        {:action "display-options" :options options})

      (= "create-testset" (first arguments))
      (cond
        (nil? (:project-id options))
        {:exit-message (missing-option-msg "create-testset" "project-id")}

        (nil? (:author-id options))
        {:exit-message (missing-option-msg "create-testset" "author-id")}

        (nil? (:testset-name options))
        {:exit-message (missing-option-msg "create-testset" "testset-name")}

        :else
        {:action "create-testset" :options options})

      (= "populate-testset" (first arguments))
      (cond
        (nil? (:project-id options))
        {:exit-message (missing-option-msg "populate-testset" "project-id")}

        (nil? (:testset-id options))
        {:exit-message (missing-option-msg "populate-testset" "testset-id")}

        :else
        {:action "populate-testset" :options options})

      (= "create-and-populate-testset" (first arguments))
      (cond
        (nil? (:project-id options))
        {:exit-message (missing-option-msg "create-and-populate-testset" "project-id")}

        (nil? (:author-id options))
        {:exit-message (missing-option-msg "create-and-populate-testset" "author-id")}

        (nil? (:testset-name options))
        {:exit-message (missing-option-msg "create-and-populate-testset" "testset-name")}

        :else
        {:action "create-and-populate-testset" :options options})

      :else
      {:exit-message (format "\nUnsupported action [%s]\n\n%s" (first arguments) (usage summary))})))
