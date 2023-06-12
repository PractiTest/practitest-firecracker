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
                 (every? #?(:clj    #(Character/isUpperCase %)
                            :cljs   #(.toUpperCase %))
                             (last results)))
          (recur (conj (vec (drop-last results))
                       (str (last results) head))
                 tail)
          (recur (conj results head) tail))))))

(defn parse-int [s]
  #?(:clj (Integer. (re-find  #"\d+" s ))
     :cljs (js/parseInt s 10)))

(defn return-error [error-message query]
  #?(:clj (throw
            (ex-info error-message
                     {:query query}))
     :cljs (str error-message query)))

(defn parse-methods [op args query]
  (condp = op
    'tokenize-package    (if (= 1 (count args))
                           (string/split (first args) #"\.")
                           (return-error "Syntax error: 'tokenize-package' must have one argument" query))
    'tokenize-class-name (if (= 1 (count args))
                           (->> (string/split (first args) #"(?=[A-Z])")
                                (map #(string/replace % "_" ""))
                                (remove string/blank?)
                                fix-abbreviations)
                           (return-error "Syntax error: 'tokenize-class-name' must have one argument" query))
    'take                (if (= 2 (count args))
                           (if (or (string? (last args)) (coll? (last args)))
                             (take (parse-int (first args)) (last args))
                             (return-error "Syntax error: 'take' second argument has to be ISeqable (String, Array etc.)" query))
                           (return-error "Syntax error: 'take' must have two arguments" query))
    'drop                (if (= 2 (count args))
                           (if (or (string? (last args)) (coll? (last args)))
                             (drop (parse-int (first args)) (last args))
                             (return-error "Syntax error: 'drop' second argument has to be ISeqable (String, Array etc.)" query))
                           (return-error "Syntax error: 'drop' must have two arguments" query))
    'drop-last           (if (<= 1 (count args) 2)
                           (if (or (string? (last args)) (coll? (last args)))
                             (apply drop-last args)
                             (return-error "Syntax error: 'drop-last' second argument has to be ISeqable (String, Array etc.)" query))
                           (return-error "Syntax error: 'drop-last' must have one or two arguments" query))
    'take-last           (case (count args)
                           1 (take-last 1 (first args))
                           2 (take-last (first args) (second args))
                           (return-error "Syntax error: 'take-last' must have one or two arguments" query))
    'concat              (apply str args)
    'capitalize          (if (= 1 (count args))
                           (if (or (string? (last args)) (coll? (last args)))
                             (map string/capitalize (first args))
                             (return-error "Syntax error: 'capitalize' argument has to be ISeqable (String, Array etc.)" query))
                           (return-error "Syntax error: 'capitalize' must have one argument" query))
    'join                (if (= 1 (count args))
                           (if (or (string? (last args)) (coll? (last args)))
                             (string/join (first args))
                             (return-error "Syntax error: 'join' argument has to be ISeqable (String, Array etc.)" query))
                           (return-error "Syntax error: 'join' must have one argument" query))
    'split               (if (= 2 (count args))
                           (let [quoted    #?(:clj  (string/escape (first args) char-escape-string)
                                              :cljs (first args))
                                 complied  #?(:clj  (java.util.regex.Pattern/compile quoted)
                                              :cljs (js/RegExp. quoted))]
                             (string/split (second args) complied))
                           (return-error "Syntax error: 'split' must have two arguments" query))
    'get               (if (= 2 (count args))
                         (take 1 (drop (- (parse-int (first args)) 1) (last args)))
                         (return-error "Syntax error: 'get' must have two arguments" query))
    'trim               (if (= 1 (count args))
                          (string/trim (first args))
                          (return-error "Syntax error: 'trim' must have only one argument" query))
    (return-error #?(:clj (format "Syntax error: unsupported function '%s'" op)
                     :cljs (str "Syntax error: unsupported function: " op)) query)))

(defn eval-query [entity-hash query]
  (if (map? query)
    (let [{:keys [op args]} query
          args              (map (partial eval-query entity-hash) args)]
      (parse-methods op args query))
    #?(:cljs (cond
               (= '?field query)                     "entity-hash"
               (string/starts-with? (str query) "?") (throw
                                                       (ex-info (str "Syntax error: unsupported variable " query)
                                                                {:query query}))
               (number? query)                       query
               :else                                 (str query))
       :clj (let [key (keyword (string/join (drop 1 (str query))))]
              (cond
                (or (= :test-suite-name key)
                    (= :test-case-name key))          (:name entity-hash)
                (and (not (= entity-hash nil))
                     (contains? entity-hash key))     (key entity-hash)
                (string/starts-with? (str query) "?") (str "")
                :else                                 (str query))))))

(defn read-query [s]
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
               (compiler query)))))

(defn try-read-query [s]
  #?(:cljs
     (try
       (read-query s)
       (catch js/Error e (str "caught exception: " e))
       (catch js/Object e
         (str "Error: " e)))))

(defn query? [obj]
  (boolean (:query (meta obj))))
