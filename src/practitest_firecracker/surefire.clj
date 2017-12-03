(ns practitest-firecracker.surefire
  (:require
   [clojure.java.io :refer [file]])
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
     (println "console-logger:DEBUG: " message))
    (^void info [_ ^String message]
     (println "console-logger:INFO: " message))
    (^void warning [_ ^String message]
     (println "console-logger:WARNING: " message))
    (^void error [_ ^String message]
     (println "console-logger:ERROR: " message))
    (^void error [_ ^Throwable cause]
     (println "console-logger:ERROR: " (.getMessage cause)))
    (^void error [_ ^String message ^Throwable cause]
     (println "console-logger:ERROR: " message ": " (.getMessage cause)))))

(defn translate-test-case [test-case]
  {:name            (.getName test-case)
   :full-name       (.getFullName test-case)
   :class-name      (.getClassName test-case)
   :full-class-name (.getFullClassName test-case)
   :time            (.getTime test-case)
   :has-failure?    (.hasFailure test-case)
   :failure-type    (.getFailureType test-case)
   :failure-message (.getFailureMessage test-case)
   :failure-detail  (.getFailureDetail test-case)})

(defn translate-test-suite [test-suite]
  {:name            (.getName test-suite)
   :package-name    (.getPackageName test-suite)
   :full-class-name (.getFullClassName test-suite)
   :time-elapsed    (.getTimeElapsed test-suite)
   :errors          (.getNumberOfErrors test-suite)
   :failures        (.getNumberOfFailures test-suite)
   :skipped         (.getNumberOfSkipped test-suite)
   :flakes          (.getNumberOfFlakes test-suite)
   :tests           (.getNumberOfTests test-suite)
   :test-cases      (map translate-test-case (.getTestCases test-suite))})

;; ===========================================================================
;; API

(defn parse-reports-dir [path]
  (let [parser (SurefireReportParser. [(file path)] (Locale/getDefault) (console-logger))]
    (map translate-test-suite (.parseXMLReportFiles parser))))
