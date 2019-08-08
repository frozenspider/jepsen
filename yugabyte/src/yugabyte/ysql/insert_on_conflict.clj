(ns yugabyte.ysql.insert-on-conflict
  (:require [clojure.string :as str]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :refer [info]]
            [jepsen [client :as client]
             [checker :as checker]
             [generator :as gen]
             [util :as util]]
            [jepsen.tests.cycle :as cycle]
            [jepsen.tests.cycle.append :as append]
            [yugabyte.ysql.client :as c]))

(def table-name
  "insert_on_conflict")

(defrecord InternalClient []
  c/YSQLYbClient

  (setup-cluster! [this test c conn-wrapper]
    (c/execute! c (j/create-table-ddl table-name [[:k :int "PRIMARY KEY"]
                                                  [:v :text]]
                                      {:conditional? true})))

  (invoke-op! [this test op c conn-wrapper]
    (case (:f op)
      :add (let [v (:value op)]
             (c/execute! c
                         ; on conflict, in postgres, needs fully qualified
                         ; column names.
                         [(str "INSERT INTO " table-name " (k, v) VALUES (?, ?) "
                               ; I think on conflict here breaks *some* of
                               ; the time, but not always, when we use a
                               ; unique constraint instead of primary key.
                               "ON CONFLICT (k) DO UPDATE SET "
                               ; Nope, it breaks both ways!
                               ; "on conflict on constraint append_k_key do update set "
                               "v = CONCAT(" table-name ".v, ',', ?)")
                          v (str v) (str v)])
             (assoc op :type :ok, :value :v))))
  )

(c/defclient Client InternalClient)
