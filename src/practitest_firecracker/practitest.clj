(ns practitest-firecracker.practitest
  (:require
    [clojure.set :refer [difference]]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [practitest-firecracker.utils :refer [print-run-time test-need-update? pformat replace-map replace-keys]]
    [practitest-firecracker.api :as api]
    [practitest-firecracker.const :refer [max-test-ids-bucket-size]]
    [practitest-firecracker.query-dsl :as query-dsl]
    [practitest-firecracker.eval :as eval]
    [clojure.pprint :as pprint]))

(defn find-sf-testset [client [project-id display-action-logs] options testset-name]
  (let [testset (api/ll-find-testset client [project-id display-action-logs] testset-name)]
    (when testset
      (eval/update-sf-testset client options testset-name testset (read-string (:id testset))))))

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
        xml-tests (flatten (map val (into {} testsets)))
        all-tests (doall (into {} (for [test xml-tests]
                                    {(eval/sf-test-suite->pt-test-name options test) test})))]
    [all-tests xml-tests testset-id-to-name ts-id-test-name-num-instances]))

(defn value-get [existing-cases xml-cases]
  (conj (into [] existing-cases) (into [] xml-cases)))

(defn connect-maps [map1 map2]
  (reduce (fn [result key1]
            (assoc result key1 [(merge (into {} (map1 key1)) (into {} (map2 key1)))]))
          {}
          (distinct (into (keys map1) (keys map2)))))

(defn update-steps [use-test-step old-tests test-cases match-step-by options new-map]
  (if use-test-step
    (map
      (fn [t]
        (let [test-id (Integer/parseInt (:id (last t)))
              test-name (first t)
              params     (get new-map test-name)
              test-test-cases (get test-cases test-id)
              t-test-cases (filter (fn [case]
                                     (if
                                       (:only-failed-steps options)
                                       (not (= "" (:failure-detail case))) true)) (:test-cases (second t)))]
          (assoc-in t
                    [1 :test-cases]
                    (map first
                         (map val
                              (let [group-by-test (group-by
                                                    (fn [case]
                                                      (reduce (fn [p param]
                                                                (or p
                                                                    (replace-map
                                                                      (if (= match-step-by "description")
                                                                        (:description case)
                                                                        (:pt-test-step-name case))
                                                                      (replace-keys param))))
                                                              false params)) test-test-cases)
                                    group-by-t (group-by
                                                 (fn [case]
                                                   (reduce (fn [p param]
                                                             (or p
                                                                 (replace-map
                                                                   (if (= match-step-by "description")
                                                                     (eval/sf-test-case->pt-step-description options case)
                                                                     (eval/sf-test-case->pt-step-name options case))
                                                                   (replace-keys param))))
                                                           false params)) t-test-cases)]
                                (connect-maps
                                  group-by-t
                                  group-by-test))))))) old-tests)
    old-tests))

(defn translate-step-attributes [attributes]
  {:pt-test-step-name (:name attributes)
   :description       (:description attributes)
   :position          (:position attributes)
   :attributes attributes})

(defn create-or-update-tests [[all-tests org-xml-tests testset-id-to-name ts-id-test-name-num-instances] client {:keys [project-id display-action-logs display-run-time use-test-step] :as options} start-time]
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

        tests-after (if (seq nil-tests)
                      ;; create missing tests and add them to the testset
                      (let [new-tests (pmap (fn [[test-name test-suite _]]
                                              [test-name test-suite (eval/create-sf-test client options test-suite)])
                                            nil-tests)]
                        new-tests)
                      ())
        log (if display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time) nil)
        new-all-tests (concat tests-after old-tests)]
    (when (seq old-tests)
      ;; update existing tests with new values
      (doall (map (fn [[_ test-suite test]]
                    (when (test-need-update? test-suite test)
                      (eval/update-sf-test client options test-suite test)))
                  old-tests))
      (when display-run-time (print-run-time "Time - after update tests: %d:%d:%d" start-time)))
    [new-all-tests org-xml-tests testset-id-to-name ts-id-test-name-num-instances test-id-to-cases tests-after]))

(defn split-n-filter-instance-params [test pt-instance-params]
  (into []
        (map
          (fn [param]
            (string/trim param))
          (filter not-empty
                  (string/split
                    (query-dsl/eval-query
                      test
                      (query-dsl/read-query pt-instance-params))
                    #"\|")))))

(defn create-instances [[all-tests org-xml-tests testset-id-to-name ts-id-test-name-num-instances test-id-to-cases tests-after]
                        client
                        {:keys [project-id display-action-logs display-run-time pt-instance-params use-test-step match-step-by] :as options} start-time]
  (when display-action-logs (log/infof "pt-instance-params: %s" pt-instance-params))
  (let [all-test-ids (map (fn [test] (:id (last test))) all-tests)
        testname-test (into {} (map (fn [test] {(first test) test}) all-tests))

        test-id-testname (into {} (map (fn [test] {(query-dsl/parse-int (:id (last test))) (first test)}) all-tests))
        testid-params (when (not-empty pt-instance-params)
                        (into {}
                              (map
                                (fn [test]
                                  (let [split-params (split-n-filter-instance-params (second test) pt-instance-params)]
                                    {(Integer/parseInt (:id (last test)))
                                     (zipmap (iterate inc 1) split-params)}))
                                all-tests)))

        log (when display-action-logs (log/infof "testid-params: %s" testid-params))

        testset-ids (map (fn [testset] (first (first testset))) testset-id-to-name)

        ts-ids (string/join "," testset-ids)

        instances (mapcat (fn [test-ids-bucket]
                            (api/ll-testset-instances client
                                                      [project-id display-action-logs]
                                                      ts-ids
                                                      (string/join "," test-ids-bucket)))
                          (partition-all max-test-ids-bucket-size all-test-ids))

        testname-to-params (when (not-empty pt-instance-params)
                             (into {}
                                   (map
                                     (fn [[key tests]]
                                       {key
                                        (into []
                                              (map
                                                (fn [test]
                                                  (zipmap
                                                    (iterate inc 1)
                                                    (split-n-filter-instance-params test pt-instance-params))) tests))})
                                     (group-by
                                       #(eval/sf-test-suite->pt-test-name options %)
                                       org-xml-tests))))

        testname-test2 (when
                         (not-empty pt-instance-params)
                         (into
                           #{}
                           (map
                             (fn [test]
                               (str
                                 (eval/sf-test-suite->pt-test-name options test) ":"
                                 (split-n-filter-instance-params test pt-instance-params)))
                             org-xml-tests)))

        filter-instances (if (not-empty pt-instance-params)
                           (filter
                             (fn [instance]
                               (let
                                 [{:keys [name bdd-parameters parameters]} (:attributes instance)
                                  obj-test (get testname-test name)
                                  test-type (:test-type (:attributes (last obj-test)))
                                  params (if (= test-type "BDDTest") bdd-parameters parameters)
                                  vals (into [] (if (map? params) (vals params) params))]
                                 (contains? testname-test2 (str name ":" vals))))
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
                                    {ts-id
                                     (merge-with -
                                                 (get ts-id-instance-num ts-id)
                                                 (frequencies (vec (map #(get-in % [:attributes :test-id])
                                                                        (get ts-id-instances (read-string ts-id))))))})))

        existing-instance (when (not-empty pt-instance-params)
                            (into {}
                                  (map
                                    (fn [[key tests]]
                                      {key
                                       (into []
                                             (map
                                               (fn [test]
                                                 (conj (get-in test [:attributes :bdd-parameters])
                                                       (get-in test [:attributes :parameters]))) tests))})
                                    (group-by
                                      (fn [inst]
                                        (get-in inst [:attributes :name]))
                                      filter-instances))))

        new-map (into {}
                      (map
                        (fn [test-name]
                          (reduce (fn [a b]
                                    (if (= (keys a) (keys b))
                                      {(first (keys a))
                                       (into [] (conj
                                                  (if (vector? (first (vals a)))
                                                    (first (vals a))
                                                    (into [] (vals a)))
                                                  (first (vals b))))}
                                      (conj a b)))
                                  {}
                                  (map (fn [x]
                                         {test-name
                                          (into {}
                                                (map (fn [y]
                                                       {(if (contains? existing-instance test-name)
                                                          (first y)
                                                          (keyword (str (first y))))
                                                        (last y)})
                                                     x))})
                                       (if (contains? existing-instance test-name)
                                         (get existing-instance test-name)
                                         (get testname-to-params test-name)))))
                        (keys testname-to-params)))

        new-testname-to-params (into {}
                                     (map
                                       (fn [test-name]
                                         {test-name
                                          (into [] (difference (set (get new-map test-name)) (set (get existing-instance test-name))))})
                                       (keys new-map)))

        tests-with-steps (update-steps use-test-step all-tests test-id-to-cases match-step-by options new-map)
        all-tests (concat tests-after tests-with-steps)
        make-instances (flatten (api/make-instances missing-instances new-testname-to-params test-id-testname))
        test-by-id (group-by (fn [test] (read-string (:id (last test)))) all-tests)
        new-intstances (flatten (for [instances-part (partition-all 100 (shuffle make-instances))]
                                  (api/ll-create-instances client [project-id display-action-logs] instances-part)))
        all-intstances (into [] (concat new-intstances (if
                                                         (not-empty pt-instance-params)
                                                         filter-instances
                                                         instances)))

        instance-to-ts-test (group-by (fn [inst] [(:set-id (:attributes inst)) (:test-id (:attributes inst))]) all-intstances)]
    (when display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time))
    [test-by-id instance-to-ts-test new-map]))

(defn make-runs [[test-by-id instance-to-ts-test new-map] client {:keys [project-id display-action-logs] :as options} start-time]
  (when display-action-logs (log/infof "make-runs"))
  (flatten (doall
             (for [[test-testset instances] instance-to-ts-test]
               (map-indexed
                 (fn [index instance]
                   (let [[ts-id test-id] test-testset
                         tst (first (get test-by-id test-id))
                         params (get new-map (get tst 0))
                         this-param  (get params index)
                         [run run-steps] (eval/sf-test-suite->run-def options (get tst 1) this-param)
                         additional-run-fields (eval/eval-additional-fields run (:additional-run-fields options))
                         additional-run-fields (merge additional-run-fields (:system-fields additional-run-fields))
                         run (eval/sf-test-run->run-def additional-run-fields (get tst 1))]
                     {:instance-id (:id instance)
                      :attributes  run
                      :steps       run-steps})) instances)))))

(defn create-runs [runs client options start-time]
  (doall (for [runs-part (partition-all 20 (shuffle runs))]
           (api/ll-create-runs client [(:project-id options) (:display-action-logs options)] runs-part))))
