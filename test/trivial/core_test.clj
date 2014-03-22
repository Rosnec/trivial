(ns trivial.core-test
  (:require [org.clojure/tools.trace :refer [dotrace]])
  (:use clojure.test
        trivial.core))

(deftest server-test
  (testing ""
    (is (= 0 1))))

