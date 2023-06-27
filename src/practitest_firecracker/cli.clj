(ns practitest-firecracker.cli
  (:require
    [clojure.tools.cli :as cli]
    [clojure.java.io :refer [file reader]]
    [clojure.tools.logging :as log]
    [clojure.walk :refer [postwalk]]
    [clojure.string :as string]
    [cheshire.core :as json]
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
   [nil "--temp-folder PATH"
    "Folder to contain parsed xmls, for each reports-path folder."
    :default "tmp"]
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
   [nil "--additional-run-fields JSON"
    "JSON containing the fields that should be added when creating PractiTest Runs"
    :default {}
    :parse-fn parse-additional-fields]
   ;; [nil "--test-case-as-pt-test" :default true]
   [nil "--test-case-as-pt-test-step" :default true]
   #_[nil "--pt-test-name DSL"
    :parse-fn read-query
    :default (read-query "(concat (join (capitalize (drop 2 (tokenize-package ?package-name)))) \": \" (join (capitalize (drop-last (tokenize-class-name ?test-suite-name)))))")]
   [nil "--pt-test-name DSL"
    :parse-fn read-query
    :default (read-query "?pt-test-name")]
   #_[nil "--pt-test-step-name DSL"
    :parse-fn read-query
    :default (read-query "(join (capitalize (tokenize-class-name ?test-case-name)))")]
   [nil "--pt-test-step-name DSL"
    :parse-fn read-query
    :default (read-query "?test-case-name")]
   [nil "--display-run-time" :default false]
   [nil "--display-action-logs" :default false]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["PractiTest reports analyzer."
        ""
        "Usage: java -jar practitest-firecracker-standaline.jar [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  create-and-populate-testset  Shortcut to perform both actions. If the TestSet with the given name already exists, it will be reused. If it exists, but has completely different set of tests, an error will be reported."
        "  version                      Will display firecracker jar file version"]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn missing-option-msg [action-name option-name]
  (format "%s is required for '%s' action" option-name action-name))

(defn parse-config-file [options]
  (let [parsed-json                   (json/parse-stream (reader (:config-path options)) true)
        new-additional-test-fields    (parse-additional-fields (json/generate-string (:additional-test-fields parsed-json)))
        new-additional-testset-fields (parse-additional-fields (json/generate-string (:additional-testset-fields parsed-json)))
        new-additional-run-fields    (parse-additional-fields (json/generate-string (:additional-run-fields parsed-json)))
        new-parsed-json               (merge parsed-json
                                             (when (:additional-testset-fields parsed-json) {:additional-testset-fields new-additional-testset-fields})
                                             (when (:additional-test-fields parsed-json) {:additional-test-fields new-additional-test-fields})
                                             (when (:additional-run-fields parsed-json) {:additional-run-fields new-additional-run-fields})
                                             (when (:pt-test-name parsed-json) {:pt-test-name (read-query (:pt-test-name parsed-json))})
                                             (when (:pt-test-step-name parsed-json) {:pt-test-step-name (read-query (:pt-test-step-name parsed-json))}))]
    new-parsed-json))

(defn parse-args [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        new-parsed-json  (when (not (= (:config-path options) nil)) (parse-config-file options))
        options          (merge options new-parsed-json)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (= "display-config" (first arguments))
      {:action "display-config" :options options}

      (= "display-options" (first arguments))
      {:action "display-options" :options options}

      (= "version" (first arguments))
      {:action "version" :options options}

      (= "create-and-populate-testset" (first arguments))
      (cond
        (nil? (:project-id options))
        {:exit-message (missing-option-msg "create-and-populate-testset" "project-id")}

        (and (nil? (:testset-name options)) (not (:multitestset options)))
        {:exit-message (missing-option-msg "create-and-populate-testset" "testset-name")}

        :else
        {:action "create-and-populate-testset" :options options})

      :else
      {:exit-message (format "\nUnsupported action [%s]\n\n%s" (first arguments) (usage summary))})))
