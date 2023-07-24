(ns fixtures.practitest-firecracker.practitest-test)

(def display-config-settings
  {:action  "display-config"
   :options {:additional-run-fields     {}
             :additional-test-fields    {}
             :additional-testset-fields {}
             :api-uri                   "https://api.practitest.com"
             :display-action-logs       false
             :display-run-time          false
             :max-api-rate              30
             :pt-test-name              '?pt-test-name
             :pt-test-step-name         '?test-case-name
             :temp-folder               "tmp"
             :test-case-as-pt-test-step true}})

(def display-options-settings
  {:action  "display-options"
   :options {:additional-run-fields     {}
             :additional-test-fields    {}
             :additional-testset-fields {}
             :api-uri                   "https://api.practitest.com"
             :display-action-logs       false
             :display-run-time          false
             :max-api-rate              30
             :pt-test-name              '?pt-test-name
             :pt-test-step-name         '?test-case-name
             :temp-folder               "tmp"
             :test-case-as-pt-test-step true}})

(def version-settings
  {:action  "version"
   :options {:additional-run-fields     {}
             :additional-test-fields    {}
             :additional-testset-fields {}
             :api-uri                   "https://api.practitest.com"
             :display-action-logs       false
             :display-run-time          false
             :max-api-rate              30
             :pt-test-name              '?pt-test-name
             :pt-test-step-name         '?test-case-name
             :temp-folder               "tmp"
             :test-case-as-pt-test-step true}})