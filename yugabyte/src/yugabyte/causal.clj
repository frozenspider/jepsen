(ns yugabyte.causal
  "Comment me"
  (:require [clojure.tools.logging :refer [debug info warn]]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.generator :as gen]
            [jepsen.tests.causal :as causal]))

(defn workload
  [opts]
  (causal/test opts))
