(ns practitest-firecracker.query-dsl
  (:require
   [clojure.string :as string]
   [clojure.edn    :as edn]))


(defn fix-abbreviations [tokens]
  (loop [results [] input tokens]
    (if (empty? input)
      results
      (let [[head & tail] input]
        (if (and (= 1 (count head))
                 (not-empty (last results))
                 (every? #(Character/isUpperCase %) (last results)))
          (recur (conj (vec (drop-last results))
                       (str (last results) head))
                 tail)
          (recur (conj results head) tail))))))

(defn parse-int [s]
  (Integer. (re-find  #"\d+" s )))

(defn eval-query [test-suite test-case query]
  (if (map? query)
    (let [{:keys [op args]} query
          args              (map (partial eval-query test-suite test-case) args)]
      (condp = op
        'tokenize-package    (if (= 1 (count args))
                               (string/split (first args) #"\.")
                               (throw
                                (ex-info "Syntax error: 'tokenize-package' must have one argument"
                                         {:query query})))
        'tokenize-class-name (if (= 1 (count args))
                               (->> (string/split (first args) #"(?=[A-Z])")
                                    (map #(string/replace % "_" ""))
                                    (remove string/blank?)
                                    fix-abbreviations)
                               (throw
                                (ex-info "Syntax error: 'tokenize-class-name' must have one argument"
                                         {:query query})))
        'take                (if (= 2 (count args))
                               (take (parse-int (first args)) (last args))
                               (throw
                                (ex-info "Syntax error: 'take' must have two arguments"
                                         {:query query})))
        'take-last           (if (= 2 (count args) 2)
                               (take-last (parse-int (first args)) (last args))
                               (throw
                                (ex-info "Syntax error: 'take-last' must have one or two arguments"
                                         {:query query})))
        'drop                (if (= 2 (count args))
                               (drop (parse-int (first args)) (last args))
                               (throw
                                (ex-info "Syntax error: 'drop' must have two arguments"
                                         {:query query})))
        'drop-last           (if (<= 1 (count args) 2)
                               (apply drop-last args)
                               (throw
                                (ex-info "Syntax error: 'drop-last' must have one or two arguments"
                                         {:query query})))
        'concat              (apply str args)
        'capitalize          (if (= 1 (count args))
                               (map string/capitalize (first args))
                               (throw
                                (ex-info "Syntax error: 'capitalize' must have one argument"
                                         {:query query})))
        'join                (if (= 1 (count args))
                               (string/join (first args))
                               (throw
                                (ex-info "Syntax error: 'join' must have one argument"
                                         {:query query})))
        (throw
         (ex-info (format "Syntax error: unsupported function '%s'" op)
                  {:query query}))))

    (let [key (keyword (string/join (drop 1 (str query))))]
      (cond
        (= :test-suite-name key)              (:name test-suite)
        (= :test-case-name key)               (:name test-case)
        (and (not (= test-suite nil))
             (contains? test-suite key))      (key test-suite)
        (and (not (= test-case nil))
             (contains? test-case key))       (key test-case)
        (string/starts-with? (str query) "?") (str "")
        :else                                 (str query)))))

(defn read-query [s]
  (let [query    (edn/read-string s)
        compiler (fn compile-query [query]
                   (if (list? query)
                     (let [[op & args] query]
                       {:op   (compile-query op)
                        :args (vec (map compile-query args))})
                     query))]
    (if (not (or (= java.lang.Long (type query))
                 (= java.lang.Double (type query))
                 (and (= java.lang.String (type query)))))
      (with-meta (compiler query)
        {:query true})
      (compiler query))))

(defn query? [obj]
  (boolean (:query (meta obj))))
