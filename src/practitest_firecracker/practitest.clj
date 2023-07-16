(ns practitest-firecracker.practitest
  (:require
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [practitest-firecracker.utils :refer [print-run-time test-need-update? pformat]]
    [practitest-firecracker.api :as api]
    [practitest-firecracker.const :refer [max-test-ids-bucket-size]]
    [practitest-firecracker.query-dsl :refer [query? eval-query]]
    [practitest-firecracker.query-dsl :refer [read-query]]
    [practitest-firecracker.eval :as eval]))

(defn find-sf-testset [client [project-id display-action-logs] options testset-name]
  (let [testset (api/ll-find-testset client [project-id display-action-logs] testset-name)]
    (when testset
      (eval/update-sf-testset client options testset-name testset (read-string (:id testset))))))

(defn make-runs [[test-by-id instance-to-ts-test] client {:keys [project-id display-action-logs] :as options} start-time]
  (when display-action-logs (log/infof "make-runs"))
  (flatten (doall
             (for [[test-testset instances] instance-to-ts-test]
               (for [instance instances]
                 (let [[ts-id test-id] test-testset
                       tst (first (get test-by-id test-id))
                       [run run-steps] (eval/sf-test-suite->run-def options (get tst 1))
                       additional-run-fields (eval/eval-additional-fields run (:additional-run-fields options))
                       additional-run-fields (merge additional-run-fields (:system-fields additional-run-fields))
                       run (eval/sf-test-run->run-def additional-run-fields (get tst 1))]
                   {:instance-id (:id instance)
                    :attributes  run
                    :steps       run-steps}))))))

(defn create-testsets [client {:keys [project-id display-action-logs] :as options} xml]
  (doall
    (for [sf-test-suites xml]
      (let [testset-name (or (:name (:attrs sf-test-suites) (:name sf-test-suites)))
            testset (or (find-sf-testset client [project-id display-action-logs] options testset-name)
                        (eval/create-sf-testset client options (:test-cases sf-test-suites) testset-name))]
        {(:id testset) (:test-list sf-test-suites)}))))

(defn group-tests [testsets _ options]
  (let [ts-id-test-name-num-instances (into {} (map (fn [[k v]]
                                                      {k
                                                       (frequencies (map (fn [test] (eval/sf-test-suite->pt-test-name options test)) v))})
                                                    (into {} testsets)))
        testset-id-to-name (map (fn [[k v]]
                                  {k (set (map
                                            (fn [test] (eval/sf-test-suite->pt-test-name options test)) v))})
                                (into {} testsets))
        tests (flatten (map val (into {} testsets)))
        all-tests (doall (into {} (for [test tests]
                                    {(eval/sf-test-suite->pt-test-name options test) test})))]
    [all-tests testset-id-to-name ts-id-test-name-num-instances]))

(defn value-get [existing-cases xml-cases]
  (conj (into [] existing-cases) (into [] xml-cases)))

(defn update-steps [use-test-step old-tests test-cases]
  (if use-test-step
    (map
      (fn [t]
        (let [test-id (Integer/parseInt (:id (last t)))]
          (assoc-in t
                    [1 :test-cases]
                    (map first
                         (map val
                              (merge-with into
                                          (group-by :pt-test-step-name (:test-cases (second t)))
                                          (group-by :pt-test-step-name (get test-cases test-id)))))))) old-tests)
    old-tests))

(defn translate-step-attributes [attributes]
  {:pt-test-step-name (:name attributes)
   :description       (:description attributes)})

(defn create-or-update-tests [[all-tests testset-id-to-name ts-id-test-name-num-instances] client {:keys [project-id display-action-logs display-run-time use-test-step] :as options} start-time]
  (let [new-tests (into [] (eval/group-test-names (vals all-tests) options))
        results (doall
                  (flatten
                    (into []
                          (pmap
                            (fn [new-tests-part]
                              (api/ll-find-tests client [project-id display-action-logs] new-tests-part)) (partition-all 20 new-tests)))))

        xml-tests (doall (for [res results]
                           [(:name_exact (:query res)) (get all-tests (:name_exact (:query res))) (first (:data (:tests res)))]))

        test-ids (->> results
                      (map #(get-in % [:tests :data]))
                      (flatten)
                      (map :id)
                      (map #(Integer/parseInt %)))

        test-cases (->> test-ids
                        (partition-all 20)
                        (pmap
                          (fn [test-ids]
                            (api/ll-test-steps client project-id (string/join "," test-ids))))
                        (into [])
                        (flatten))

        test-id-to-cases (into {}
                               (map (fn [[grp-key values]]
                                      {grp-key (map #(translate-step-attributes (:attributes %)) values)})
                                    (group-by (fn [x] (:test-id (:attributes x))) test-cases)))

        log (if display-run-time (print-run-time "Time - after find all tests: %d:%d:%d" start-time) nil)
        nil-tests (filter #(nil? (last %)) xml-tests)
        old-tests (filter #(not (nil? (last %))) xml-tests)

        tests-with-steps (update-steps use-test-step old-tests test-id-to-cases)
        tests-after (if (seq nil-tests)
                      ;; create missing tests and add them to the testset
                      (let [new-tests (pmap (fn [[test-name test-suite _]]
                                              [test-name test-suite (eval/create-sf-test client options test-suite)])
                                            nil-tests)]
                        new-tests)
                      ())
        log (if display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time) nil)
        new-all-tests (concat tests-after tests-with-steps)]
    (when (seq tests-with-steps)
      ;; update existing tests with new values
      (doall (map (fn [[_ test-suite test]]
                    (when (test-need-update? test-suite test)
                      (eval/update-sf-test client options test-suite test)))
                  tests-with-steps))
      (when display-run-time (print-run-time "Time - after update tests: %d:%d:%d" start-time)))
    [new-all-tests testset-id-to-name ts-id-test-name-num-instances]))

(defn create-instances [[all-tests testset-id-to-name ts-id-test-name-num-instances] client {:keys [project-id display-action-logs display-run-time pt-instance-params] :as options} start-time]
  (let [all-test-ids (map (fn [test] (:id (last test))) all-tests)
        testname-test (into {} (map (fn [test] {(first test) test}) all-tests))
        testid-params (when (not-empty pt-instance-params)
                        (into {}
                              (map
                                (fn [test]
                                  (let [split-params (string/split
                                                       (eval-query
                                                         (second test)
                                                         (read-query pt-instance-params))
                                                       #"\|")]
                                    {(Integer/parseInt (:id (last test)))
                                     (when (= (mod (count split-params) 2) 0)
                                       (apply array-map
                                              (string/split
                                                (eval-query
                                                  (second test)
                                                  (read-query pt-instance-params))
                                                #"\|")))}))
                                all-tests)))
        testset-ids (map (fn [testset] (first (first testset))) testset-id-to-name)
        ts-ids (string/join "," testset-ids)
        instances (mapcat (fn [test-ids-bucket]
                            (api/ll-testset-instances client
                                                      [project-id display-action-logs]
                                                      ts-ids
                                                      (string/join "," test-ids-bucket)))
                          (partition-all max-test-ids-bucket-size all-test-ids))
        filter-instances (if pt-instance-params
                           (filter
                             (fn [instance]
                               (let
                                 [{:keys [name bdd-parameters parameters]} (:attributes instance)
                                  [_ xml-test test] (get testname-test name)
                                  split-params (string/split
                                                 (eval-query xml-test (read-query pt-instance-params)) #"\|")
                                  xml-params (into {} (map (fn [[key value]] {(keyword key) value})
                                                           (when (= (mod (count split-params) 2) 0)
                                                             (apply array-map split-params))))
                                  test-type (:test-type (:attributes test))
                                  params (if (= test-type "BDDTest") bdd-parameters parameters)]
                                 (= xml-params params)))
                             instances)
                           instances)

        ts-id-instance-num (into {} (map (fn [testset-id-name]
                                           {(first (first testset-id-name))
                                            (into {}
                                                  (map
                                                    (fn [test-name]
                                                      {(read-string (:id (last (get testname-test test-name))))
                                                       (get (get ts-id-test-name-num-instances (first (first testset-id-name))) test-name)})
                                                    (last (last testset-id-name))))})
                                         testset-id-to-name))
        ts-id-instances (group-by (fn [inst] (get-in inst [:attributes :set-id])) filter-instances)
        missing-instances (into {}
                                (doall
                                  (for [ts-id (into () testset-ids)]
                                    {ts-id (merge-with -
                                                       (get ts-id-instance-num ts-id)
                                                       (frequencies (vec (map #(get-in % [:attributes :test-id]) (get ts-id-instances (read-string ts-id))))))})))
        make-instances (flatten (api/make-instances missing-instances testid-params))
        test-by-id (group-by (fn [test] (read-string (:id (last test)))) all-tests)
        new-intstances (flatten (for [instances-part (partition-all 100 (shuffle make-instances))]
                                  (api/ll-create-instances client [project-id display-action-logs] instances-part)))
        all-intstances (into [] (concat new-intstances instances))
        instance-to-ts-test (group-by (fn [inst] [(:set-id (:attributes inst)) (:test-id (:attributes inst))]) all-intstances)]
    (when display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time))
    [test-by-id instance-to-ts-test]))

(defn create-runs [runs client options start-time]
  (doall (for [runs-part (partition-all 20 (shuffle runs))]
           (api/ll-create-runs client [(:project-id options) (:display-action-logs options)] runs-part))))
