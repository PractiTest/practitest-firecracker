(ns fixtures.practitest-firecracker.config)

(def configA
  {"additional-test-fields"
   {"custom-fields" {}
    "system-fields" {}}
   "additional-testset-fields" {"custom-fields" {}
                                "system-fields" {}}
   "project-id" 2
   "email" "shmuel@practitest.com"
   "testset-name" "Firecracker Testset"
   "test-case-as-pt-test-step" true
   "multitestset" false
   "pt-test-name" "?pt-test-name"
   "pt-test-step-name" "?pt-test-step-name"
   "api-token" "aaa"})
