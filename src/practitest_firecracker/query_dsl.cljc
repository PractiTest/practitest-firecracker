(ns practitest-firecracker.query-dsl
  (:require
   [clojure.string :as string]
   [clojure.edn    :as edn]
   #?(:clj [java.util.regex Pattern]
      :cljs [goog.string :as gstring])))


(defn fix-abbreviations [tokens]
  (loop [results [] input tokens]
    (if (empty? input)
      results
      (let [[head & tail] input]
        (if (and (= 1 (count head))
                 (not-empty (last results))
                 (every? #?(:clj #(Character/isUpperCase %)
                            :cljs #(js/toUpperCase  %))
                         (last results)))
          (recur (conj (vec (drop-last results))
                       (str (last results) head))
                 tail)
          (recur (conj results head) tail))))))

(defn parse-int [s]
  #?(:clj (Integer. (re-find  #"\d+" s ))
     :cljs (js/parseInt (re-find  #"\d+" s ))))

(defn eval-query [val query]
  (if (map? query)
    (let [{:keys [op args]} query
          args              (map (partial eval-query val) args)]
      (condp = op
        'tokenize-package    (if (= 1 (count args))
                               (string/split (first args) #"\.")
                               (str "Syntax error: 'tokenize-package' must have one argument " query))
        'tokenize-class-name (if (= 1 (count args))
                               (->> (string/split (first args) #"(?=[A-Z])")
                                    (map #(string/replace % "_" ""))
                                    (remove string/blank?)
                                    fix-abbreviations
                                    )
                               (str "Syntax error: 'tokenize-class-name' must have one argument " query))
        'take                (if (= 2 (count args))
                               (if (or (string? (last args)) (coll? (last args)))
                                 (take (js/parseInt (first args)) (last args))
                                 (throw
                                   (ex-info "Syntax error: 'take' second argument has to be ISeqable (String, Array etc.)"
                                            {:query query})))
                               (throw
                                 (ex-info "Syntax error: 'take' must have two arguments"
                                          {:query query})))
        'drop                (if (= 2 (count args))
                               (if (or (string? (last args)) (coll? (last args)))
                                 (drop (js/parseInt (first args)) (last args))
                                 (throw
                                   (ex-info "Syntax error: 'drop' second argument has to be ISeqable (String, Array etc.)"
                                            {:query query})))
                               (throw
                                 (ex-info "Syntax error: 'drop' must have two arguments"
                                          {:query query})))
        'drop-last           (if (<= 1 (count args) 2)
                               (if (or (string? (last args)) (coll? (last args)))
                                 (apply drop-last args)
                                 (throw
                                   (ex-info "Syntax error: 'drop-last' second argument has to be ISeqable (String, Array etc.)"
                                            {:query query})))
                               (throw
                                 (ex-info "Syntax error: 'drop-last' must have one or two arguments"
                                          {:query query})))
        'concat              (apply str args)
        'capitalize          (if (= 1 (count args))
                               (if (or (string? (last args)) (coll? (last args)))
                                 (map string/capitalize (first args))
                                 (throw
                                   (ex-info "Syntax error: 'capitalize' argument has to be ISeqable (String, Array etc.)"
                                            {:query query})))
                               (throw
                                 (ex-info "Syntax error: 'capitalize' must have one argument"
                                          {:query query})))
        'join                (if (= 1 (count args))
                               (if (or (string? (last args)) (coll? (last args)))
                                 (string/join (first args))
                                 (throw
                                   (ex-info "Syntax error: 'join' argument has to be ISeqable (String, Array etc.)"
                                            {:query query})))
                               (throw
                                 (ex-info "Syntax error: 'join' must have one argument"
                                          {:query query})))
        'split               (if (= 2 (count args))
                               (let [quoted    (js/RegExp.    (first args))
                                     (string/replace "The color is red." #"[(?^$]]"  #(str "\\" %1))
                                     complied  (Pattern/compile  quoted)]
                                 (string/split (second args) complied))
                               (throw
                                 (ex-info "Syntax error: 'split' must have two arguments"
                                          {:query query})))
        'get               (if (= 2 (count args))
                             (take (drop (parse-int (first args)) (last args)))
                             (throw
                               (ex-info "Syntax error: 'get' must have two arguments"
                                        {:query query})))
        'trim               (if (= 1 (count args))
                              (string/trim (first args))
                              (throw
                                (ex-info "Syntax error: 'trim' must have only one argument"
                                         {:query query})))
        (throw
          (ex-info (str "Syntax error: unsupported function: " op)
                   {:query query}))
        ))
    (cond
      (= '?field query)                     val
      (string/starts-with? (str query) "?") (throw
                                              (ex-info (str "Syntax error: unsupported variable " query)
                                                       {:query query}))
      (number? query)                       query
      :else                                 (str query))))


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
        'take-last           (case (count args)
                               1 (take-last 1 (first args))
                               2 (take-last (first args) (second args))
                               (throw
                                 (ex-info "Syntax error: 'take-last' must have one or two arguments"
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
        'split               (if (= 2 (count args))
                               (let [quoted    #?(:clj  (Pattern/quote (first args))
                                                  :cljs (first args))
                                     complied  #?(:clj  (Pattern/compile quoted)
                                                  :cljs (gstring/regexp quoted))]
                                 (string/split (second args) complied))
                               (throw
                                 (ex-info "Syntax error: 'split' must have two arguments"
                                          {:query query})))
        'get               (if (= 2 (count args))
                               (take (drop (parse-int (first args)) (last args)))
                               (throw
                                 (ex-info "Syntax error: 'get' must have two arguments"
                                          {:query query})))
        'trim               (if (= 1 (count args))
                             (string/trim (first args))
                             (throw
                               (ex-info "Syntax error: 'trim' must have only one argument"
                                        {:query query})))
        (throw
         (ex-info
           #?(:clj (format "Syntax error: unsupported function '%s'" op)
              :cljs (str "Syntax error: unsupported function: " op))
                  {:query query}))))
    #?(:cljs (cond
               (= '?field query)                     val
               (string/starts-with? (str query) "?") (throw
                                                       (ex-info (str "Syntax error: unsupported variable " query)
                                                                {:query query}))
               (number? query)                       query
               :else                                 (str query))
       :clj (let [key (keyword (string/join (drop 1 (str query))))]
              (cond
                (= :test-suite-name key)              (:name test-suite)
                (= :test-case-name key)               (:name test-case)
                (and (not (= test-suite nil))
                     (contains? test-suite key))      (key test-suite)
                (and (not (= test-case nil))
                     (contains? test-case key))       (key test-case)
                (string/starts-with? (str query) "?") (str "")
                :else                                 (str query))))))

(defn read-query [s]
  (try
    (let [query    (edn/read-string s)
          compiler (fn compile-query [query]
                     (if (list? query)
                       (let [[op & args] query]
                         {:op   (compile-query op)
                          :args (vec (map compile-query args))})
                       query))]
      #?(:cljs (if (and (not (number? query)) (not (string? query)))
                 (with-meta (compiler query)
                            {:query true})
                 (compiler query))
         :clj  (if (not (or (= java.lang.Long (type query))
                            (= java.lang.Double (type query))
                            (and (= java.lang.String (type query)))))
                 (with-meta (compiler query)
                            {:query true})
                 (compiler query))))
    #?(:cljs (catch js/Error e (str "caught exception: " e)))
    #?(:cljs (catch js/Object e
               (str "Error: " e)))))

(defn query? [obj]
  (boolean (:query (meta obj))))
