(ns yugabyte.causal-reverse
  "Comment me"
  (:require [clojure.tools.logging :refer [debug info warn]]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.generator :as gen]
            [jepsen.tests.causal-reverse :as causal-reverse]))

(defn workload
  [opts]
  (causal-reverse/workload opts))
