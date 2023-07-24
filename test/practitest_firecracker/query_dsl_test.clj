(ns practitest-firecracker.query-dsl-test
  (:require [clojure.test :refer :all]
            [practitest-firecracker.query-dsl :refer :all]))

(deftest test-parse-int
  (is (= 123 (parse-int "123"))))

(deftest test-fix-abbreviations
  (is
    (=
      (fix-abbreviations '("var" "var2" "A" "B" "C" "TxT" "A" "x" "O" "." "V" "v" "m"))
      ["var" "var2" "ABC" "TxT" "Ax" "O." "Vv" "m"])))

(deftest test-parse-methods
  (testing "parse-methods 'tokenize-package"
    (is
      (=
        (parse-methods 'tokenize-package ["A.S.C"] "(tokenize-package [\"A.S.C\"]])")
        ["A" "S" "C"])))
  (testing "parse-methods 'tokenize-class-name"
    (is
      (=
        (parse-methods 'tokenize-class-name ["AaSxCc"] "(tokenize-class-name [\"AaSxCc\"]])")
        ["Aa" "Sx" "Cc"])))
  (testing "parse-methods 'take"
    (is
      (=
        (parse-methods 'take '("2" "ABC") "(take \"2\" \"ABC\")")
        '(\A \B))))
  (testing "parse-methods 'drop"
    (is
      (=
        (parse-methods 'drop '("2" "ABC") "(drop \"2\" \"ABC\")")
        '(\C))))
  (testing "parse-methods 'drop-last"
    (is
      (=
        (parse-methods 'drop-last '("ABC") "(drop-last \"ABC\")")
        '(\A \B))))
  (testing "parse-methods 'take-last"
    (is
      (=
        (parse-methods 'take-last '("ABC") "(take-last \"ABC\")")
        '(\C))))
  (testing "parse-methods 'concat"
    (is
      (=
        (parse-methods 'concat '("ABC" "EFG") "(concat '(\"ABC\" \"EFG\"))")
        "ABCEFG")))
  (testing "parse-methods 'capitalize"
    (is
      (=
        (parse-methods 'capitalize '("abc") "(capitalize \"abc\")")
        '("A" "B" "C"))))
  (testing "parse-methods 'join"
    (is
      (=
        (parse-methods 'join '(["abc" "efg"]) "(join '([\"abc\" \"efg\"]))")
        "abcefg")))
  (testing "parse-methods 'split"
    (is
      (=
        (parse-methods 'split '(" " "abc efg") "(split '(\" \" \"abc efg\"))")
        ["abc" "efg"])))
  (testing "parse-methods 'get"
    (is
      (=
        (parse-methods 'get '("2" ["a" "b" "c" "d" "e" "f"]) "(get '(\"2\" [\"a\" \"b\" \"c\" \"d\" \"e\" \"f\"]))")
        "b")))
  (testing "parse-methods 'trim"
    (is
      (=
        (parse-methods 'trim '("   123   ") "(trim \"   123   \")")
        "123"))))

(deftest test-eval-query
  (testing "eval-query ?test-suite-name"
    (is
      (=
        (eval-query {:name "the name"} "?test-suite-name")
        "the name")))
  (testing "eval-query ?test-case-name"
    (is
      (=
        (eval-query {:name "the name"} "?test-case-name")
        "the name")))
  (testing "eval-query ?name"
    (is
      (=
        (eval-query {:name "the name"} "?name")
        "the name")))
  (testing "eval-query trim get split query"
    (is
      (=
        (eval-query {:name "the name"} {:op 'trim :args [{:op 'get :args [1 {:op 'split :args ["n" "?name"]}]}]})
        "the"))))

(deftest test-read-query
  (testing "read-query"
    (is
      (=
        (read-query "(get 1 [\"val\"])")
        {:op 'get
         :args [1 ["val"]]}))))