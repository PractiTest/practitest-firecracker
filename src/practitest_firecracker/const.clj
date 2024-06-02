(ns practitest-firecracker.const)

(def ^:const fc-version "2.1.2")

(def ^:const testset-instances-uri "/projects/%d/instances.json")
(def ^:const test-uri "/projects/%d/tests/%d.json")
(def ^:const test-steps-uri "/projects/%d/steps.json")
(def ^:const create-test-uri "/projects/%d/tests.json")
(def ^:const create-testset-uri "/projects/%d/sets.json")
(def ^:const create-instance-uri "/projects/%d/instances.json")
(def ^:const create-run-uri "/projects/%d/runs.json")
(def ^:const update-test-uri "/projects/%d/tests/%d.json")
(def ^:const update-testset-uri "/projects/%d/sets/%d.json")
(def ^:const list-tests-uri "/projects/%d/tests.json")
(def ^:const bulk-list-tests-uri "/projects/%d/tests/bulk_search.json")
(def ^:const list-testsets-uri "/projects/%d/sets.json")
(def ^:const custom-field-uri "/projects/%d/custom_fields/%d.json")

;; Used when we get testset instances for multiple test ids
;; It's a GET request, so if we pass too many test IDs, we get the "URL too long" error
;; 50 sounds like a good compromise.

(def ^:const max-test-ids-bucket-size 50)
