(ns practitest-firecracker.parser.utils
  (:require
   [clojure.pprint  :as pprint]
   [clojure.string  :as str]))

(defn return-file [file]
  (pprint/pprint (slurp file)))

(defn return-files [directory]
  (let [filtered-files   (filter (fn [file] (str/ends-with? (.getAbsolutePath file) ".xml")) (file-seq directory))
        filtered-paths   (for [file filtered-files] (.getAbsolutePath file))
        files            (for [path filtered-paths] (return-file path))]
    files))
