(ns user
  (:require
   [clojure.repl   :refer [doc source]]
   [clojure.pprint :refer [pprint]]
   [practitest-firecracker.parser.core :as parser]))

(comment

  (parser/zip-str (slurp "test-data/xunit/robot-framework-globe-example.xml"))
  )
