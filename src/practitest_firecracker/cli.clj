(ns practitest-firecracker.cli
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.java.io   :refer [file]]
   [clojure.string    :as string]
   [cheshire.core     :as json]))

(def cli-options
  [[nil "--api-uri URI" "API URI" :default "https://api.practitest.com"]
   [nil "--api-token TOKEN" "API token"]
   [nil "--email EMAIL" "Your email address"]
   [nil "--reports-path PATH" "Path to surefire reports directory"
    :validate [#(.exists (file %)) "Directory doesn't exist"]]
   [nil "--project-id PROJECT-ID" "PractiTest Project ID"
    :parse-fn #(Integer/parseInt %)]
   [nil "--testset-name TESTSET-NAME" "PractiTest TestSet name"]
   [nil "--testset-id TESTSET-ID" "PractiTest TestSet ID"
    :parse-fn #(Integer/parseInt %)]
   [nil "--author-id AUTHOR-ID" "PractiTest user ID"]
   [nil "--additional-test-fields JSON"
    "JSON containing the fields that should be added when creating PractiTest Test"
    :default {}
    :parse-fn #(json/parse-string % true)]
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
        "  create-testset    Analyzes the given Surefire reports directory and creates a TestSet with Tests and Steps in PractiTest to reflect"
        "                    the structure of the report. Returns a TestSet ID that you should use to run the 'populate-testset' action"
        "  populate-testset  Analyzes the given Surefire reports directory and populates the given TestSet with the data from the report"
        ""]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn parse-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (= "create-testset" (first arguments))
      (cond
        (nil? (:project-id options))
        {:exit-message "project-id is required for 'create-testset' action"}

        (nil? (:author-id options))
        {:exit-message "author-id is required for 'create-testset' action"}

        (nil? (:testset-name options))
        {:exit-message "testset-name is required for 'create-testset' action"}

        :else
        {:action "create-testset" :options options})

      (= "populate-testset" (first arguments))
      (cond
        (nil? (:project-id options))
        {:exit-message "project-id is required for 'populate-testset' action"}

        (nil? (:testset-id options))
        {:exit-message "testset-id is required for 'populate-testset' action"}

        :else
        {:action "populate-testset" :options options})

      :else
      {:exit-message (usage summary)})))
