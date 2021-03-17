(ns practitest-firecracker.surefire
  (:require
   [clojure.java.io       :refer [file]]
   [clojure.tools.logging :as log])
  (:import
   (java.util Locale)
   (org.apache.maven.plugins.surefire.report SurefireReportParser)
   (org.apache.maven.plugin.surefire.log.api ConsoleLogger)))

;; ===========================================================================
;; utils

(defn console-logger []
  ;; since ConsoleLogger uses multiple arity and type dispatch
  ;; we have to annotate it with type hints
  ;; and since you can't just annotate some of the types,
  ;; we had to provide type hints for return types too
  ;; all in all, it's a little more verbose than i like
  (reify ConsoleLogger
    (^void debug [_ ^String message]
     (log/debug message))
    (^void info [_ ^String message]
     (log/info message))
    (^void warning [_ ^String message]
     (log/warn message))
    (^void error [_ ^String message]
     (log/error message))
    (^void error [_ ^Throwable cause]
     (log/error cause))
    (^void error [_ ^String message ^Throwable cause]
     (log/error cause message))))

(defn has-failure? [test-case]
  ;; the parser is not always picking up that there was a failure
  (boolean (or (.hasFailure test-case)
               (.getFailureMessage test-case)
               (.getFailureDetail test-case))))

(defn translate-test-case [test-case]
  {:name            (.getName test-case)
   :full-name       (.getFullName test-case)
   :class-name      (.getClassName test-case)
   :full-class-name (.getFullClassName test-case)
   :time            (.getTime test-case)
   :has-failure?    (has-failure? test-case)
   :failure-type    (.getFailureType test-case)
   :failure-message (.getFailureMessage test-case)
   :failure-detail  (.getFailureDetail test-case)
   :raw test-case
   })

(defn translate-test-suite [test-suite]
  {:name-test-suite            (.getName test-suite)
   :package-name    (.getPackageName test-suite)
   :full-class-name (.getFullClassName test-suite)
   :time-elapsed    (.getTimeElapsed test-suite)
   :errors          (.getNumberOfErrors test-suite)
   :failures        (.getNumberOfFailures test-suite)
   :skipped         (.getNumberOfSkipped test-suite)
   :flakes          (.getNumberOfFlakes test-suite)
   :tests           (.getNumberOfTests test-suite)
   :raw test-suite
   :test-cases      (map translate-test-case (.getTestCases test-suite))})

;; ===========================================================================
;; API

(defn parse-reports-dir [paths]
  (let [parser (SurefireReportParser. (map file paths) (Locale/getDefault) (console-logger))]
    (map translate-test-suite (.parseXMLReportFiles parser))))
