(ns yugabyte.ysql.causal
  (:require [clojure.java.jdbc :as j]
            [clojure.string :as str]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen.client :as client]
            [jepsen.independent :as independent]
            [jepsen.reconnect :as rc]
            [yugabyte.ysql.client :as c]))

(def table-name "causal")

(defrecord YSQLCausalYbClient []
  c/YSQLYbClient

  (setup-cluster! [this test c conn-wrapper]
    (c/execute! c (j/create-table-ddl table-name [[:id :int "PRIMARY KEY"]
                                                  [:val :int]])))


  (invoke-op! [this test op c conn-wrapper]
    (let [[id new-val] (:value op)]
      (case (:f op)
        :read-init
        (do (assert (= new-val nil))
            (let [old-val (c/select-single-value c table-name :val (str "id = " id))]
              (if (= nil old-val)
                (do (c/insert! c table-name {:id id, :val new-val})
                    (assoc op :type :ok :value (independent/tuple id 0)))
                (assoc op :type :fail, :value (independent/tuple id old-val)))))

        :read
        (let [val (c/select-single-value c table-name :val (str "id = " id))]
          (assoc op :type :ok, :value (independent/tuple id (or val 0))))

        :write
        (do (c/update! c table-name {:val new-val} ["id = ?" id])
            (assoc op :type :ok)))))


  (teardown-cluster! [this test c conn-wrapper]
    (c/drop-table c table-name)))


(c/defclient YSQLCausalClient YSQLCausalYbClient)
