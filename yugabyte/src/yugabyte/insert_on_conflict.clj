(ns yugabyte.insert-on-conflict
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info]]
            [jepsen.generator :as gen]
            [jepsen.checker :as checker]
            [yugabyte.generator :as ygen]))

(defn add [_ _] {:type :invoke, :f :add, :value 123})

(defn workload
  [opts]
  {:generator (->> add
                   (gen/stagger 1/10)
                   (ygen/with-op-index))
   :checker   (checker/noop)})
