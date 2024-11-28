(ns build
  (:require
    [clojure.tools.build.api :as b]))

(def lib 'practitest/practitest-firecracker)

; Create new tag v<Major>.<Minor> when starting working on new major/minor version branch
(def major-version 2)
(def minor-version 2)

(def commits-count (b/git-process {:git-args (format "rev-list v%s.%s..HEAD --count" major-version minor-version)}))
(def version (format "%s.%s.%s" major-version minor-version commits-count))
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (println "Cleaning directories")
  (b/delete {:path "target"}))

(defn uber [_]
  (println "Building uberjar for" version)
  (clean nil)
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (println "Compiling clojure code")
  (b/compile-clj {:basis @basis
                  :ns-compile '[practitest-firecracker.core]
                  :class-dir class-dir})
  (println "Packing JAR file")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'practitest-firecracker.core}))
