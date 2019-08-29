(ns yugabyte.nemesis
  (:require [clojure.tools.logging :refer :all]
            [clojure.pprint :refer [pprint]]
            [jepsen.control :as c]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as nemesis]
            [jepsen.util :as util :refer [meh timeout]]
            [jepsen.nemesis.time :as nt]
            [slingshot.slingshot :refer [try+]]
            [yugabyte.auto :as auto]))

(def max-skew-ms
  "Max clock skew to be used by test"
  40)

(defn rand-skew-ms
  "Get random clock skew between 5 ms (inclusive) and max-skew-ms (exclusive)"
  []
  (+ 5 (rand (- max-skew-ms 5))))

(defn rand-half-skew-ms
  "!"
  []
  (/ (double (rand-skew-ms)) 2))

(defn process-nemesis
  "A nemesis that can start, stop, and kill randomly selected subsets of
  nodes."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (let [nodes (:nodes test)
            nodes (case (:f op)
                    (:resume-tserver :start-tserver) nodes
                    (:resume-master :start-master)  (auto/master-nodes test)

                    (:stop-tserver :kill-tserver :pause-tserver)
                    (util/random-nonempty-subset nodes)

                    (:stop-master :kill-master :pause-master)
                    (util/random-nonempty-subset (auto/master-nodes test)))
            db (:db test)]
        (assoc op :value
               (c/on-nodes test nodes
                 (fn [test node]
                   (case (:f op)
                     :start-master  (auto/start-master!  db test node)
                     :start-tserver (auto/start-tserver! db test node)
                     :stop-master   (auto/stop-master!   db)
                     :stop-tserver  (auto/stop-tserver!  db)
                     :kill-master   (auto/kill-master!   db)
                     :kill-tserver  (auto/kill-tserver!  db)
                     :pause-master    (auto/signal! "yb-master"   :STOP)
                     :pause-tserver   (auto/signal! "yb-tserver"  :STOP)
                     :resume-master   (auto/signal! "yb-master"   :CONT)
                     :resume-tserver  (auto/signal! "yb-tserver"  :CONT)))))))

    (teardown! [this test])))

(defn clock-nemesis-wrapper
  "Wrapper around standard Jepsen clock-nemesis which stops ntp service in addition to ntpd.
  Won't be needed after https://github.com/jepsen-io/jepsen/pull/397"
  []
  (let [clock-nemesis (nt/clock-nemesis)]
    (reify nemesis/Nemesis
      (setup! [nem test]
        (c/with-test-nodes test (nt/install!))
        ; Try to stop ntpd service in case it is present and running.
        (c/with-test-nodes test
                           (try (c/su (c/exec :service :ntp :stop))
                                (catch RuntimeException e))
                           (try (c/su (c/exec :service :ntpd :stop))
                                (catch RuntimeException e)))
        (nt/reset-time! test)
        nem)

      (invoke! [_ test op] (nemesis/invoke! clock-nemesis test op))

      (teardown! [_ test] (nemesis/teardown! clock-nemesis test)))))

(defn full-nemesis
  "Merges together all nemeses"
  []
  (nemesis/compose
    {#{:start-master  :start-tserver
       :stop-master   :stop-tserver
       :kill-master   :kill-tserver
       :pause-master  :pause-tserver
       :resume-master :resume-tserver} (process-nemesis)
     {:start-partition :start
      :stop-partition  :stop}         (nemesis/partitioner nil)
     {:reset-clock          :reset
      :strobe-clock         :strobe
      :check-clock-offsets  :check-offsets
      :bump-clock           :bump}    (clock-nemesis-wrapper)}))

; Generators

(defn op
  "Shorthand for constructing a nemesis op"
  ([f]
   (op f nil))
  ([f v]
   {:type :info, :f f, :value v})
  ([f v & args]
   (apply assoc (op f v) args)))

(defn partition-one-gen
  "A generator for a partition that isolates one node."
  [test process]
  (op :start-partition
     (->> test :nodes nemesis/split-one nemesis/complete-grudge)
     :partition-type :single-node))

(defn partition-half-gen
  "A generator for a partition that cuts the network in half."
  [test process]
  (op :start-partition
      (->> test :nodes shuffle nemesis/bisect nemesis/complete-grudge)
      :partition-type :half))

(defn partition-ring-gen
  "A generator for a partition that creates overlapping majority rings"
  [test process]
  (op :start-partition
      (->> test :nodes nemesis/majorities-ring)
      :partition-type :ring))

(defn reset-gen
  "Randomized reset generator. Performs resets on random subsets of the tests'
  nodes."
  [test process]
  {:type :info, :f :reset, :value (util/random-nonempty-subset (:nodes test))})

(defn bump-gen
  "Randomized clock bump generator. On random subsets of nodes, bumps the clock
  from -max-skew-ms to +max-skew-ms."
  [test process]
  {:type  :info
   :f     :bump
   :value (zipmap (util/random-nonempty-subset (:nodes test))
                  (repeatedly (fn []
                                (long (* (rand-nth [-1 1])
                                         (rand-half-skew-ms))))))})

(defn strobe-gen
  "Randomized clock strobe generator. On random subsets of the test's nodes,
  introduces clock strobes from 4 ms to max-skew-ms, with a period of 1 ms to
  1 second, for a duration of 0-32 seconds."
  [test process]
  {:type  :info
   :f     :strobe
   :value (zipmap (util/random-nonempty-subset (:nodes test))
                  (repeatedly (fn []
                                {:delta (long (rand-half-skew-ms))
                                 :period (long (Math/pow 2 (rand 10)))
                                 :duration (rand 32)})))})

(defn clock-gen-base
  "Emits a random schedule of clock skew operations. Always starts by checking
  the clock offsets to establish an initial bound."
  []
  (gen/phases
    (gen/once {:type :info, :f :check-offsets})
    (gen/mix [reset-gen bump-gen strobe-gen])))

(defn clock-gen
  "A mixture of clock operations."
  []
  (->> (clock-gen-base) ; (nt/clock-gen)
       (gen/f-map {:check-offsets  :check-clock-offsets
                   :reset          :reset-clock
                   :strobe         :strobe-clock
                   :bump           :bump-clock})))

(defn flip-flop
  "Switches between ops from two generators: a, b, a, b, ..."
  [a b]
  (gen/seq (cycle [a b])))

(defn opt-mix
  "Given a nemesis map n, and a map of options to generators to use if that
  option is present in n, constructs a mix of generators for those options. If
  no options match, returns `nil`."
  [n possible-gens]
  (let [gens (reduce (fn [gens [option gen]]
                       (if (option n)
                         (conj gens gen)
                         gens))
                     []
                     possible-gens)]
    (when (seq gens)
      (gen/mix gens))))

(defn mixed-generator
  "Takes a nemesis options map `n`, and constructs a generator for all nemesis
  operations. This generator is used during normal nemesis operations."
  [n]
  ; Shorthand: we're going to have a bunch of flip-flops with various types of
  ; failure conditions and a single recovery.
  (let [o (fn [possible-gens recovery]
            ; We return nil when mix does to avoid generating flip flops when
            ; *no* options are present in the nemesis opts.
            (when-let [mix (opt-mix n possible-gens)]
              (flip-flop mix recovery)))]

    ; Mix together our different types of process crashes, partitions, and
    ; clock skews.
    (->> [(o {:kill-tserver (op :kill-tserver)
              :stop-tserver (op :stop-tserver)}
             (op :start-tserver))
          (o {:kill-master (op :kill-master)
              :stop-master (op :stop-master)}
             (op :start-master))
          (o {:pause-tserver (op :pause-tserver)}
             (op :resume-tserver))
          (o {:pause-master (op :pause-master)}
             (op :resume-master))
          (o {:partition-one  partition-one-gen
              :partition-half partition-half-gen
              :partition-ring partition-ring-gen}
             (op :stop-partition))
          (opt-mix n {:clock-skew (clock-gen)})]
         ; For all options relevant for this nemesis, mix them together
         (remove nil?)
         gen/mix
         ; Introduce either random or fixed delays between ops
         ((case (:schedule n)
            (nil :random)    gen/stagger
            :fixed           gen/delay-til)
          (:interval n)))))

(defn final-generator
  "Takes a nemesis options map `n`, and constructs a generator to stop all
  problems. This generator is called at the end of a test, before final client
  operations."
  [n]
  (->> (cond-> []
         (:clock-skew n)                          (conj :reset-clock)
         (:pause-master n)                        (conj :resume-master)
         (:pause-tserver n)                       (conj :resume-tserver)
         (or (:kill-tserver n) (:stop-tserver n)) (conj :start-tserver)
         (or (:kill-master n)  (:stop-master n))  (conj :start-master)

         (some n [:partition-one :partition-half :partition-ring])
         (conj :stop-partition))
       (map op)
       gen/seq))

(defn full-generator
  "Takes a nemesis options map `n`. If `n` has a :long-recovery option, builds
  a generator which alternates between faults (mixed-generator) and long
  recovery windows (final-generator). Otherwise, just emits faults from
  mixed-generator."
  [n]
  (if (:long-recovery n)
    (let [mix     #(gen/time-limit 120 (mixed-generator n))
          recover #(gen/phases (final-generator n)
                               (gen/sleep 60))]
      (gen/seq-all (interleave (repeatedly mix)
                               (repeatedly recover))))
    (mixed-generator n)))

(defn expand-options
  "We support shorthand options in nemesis maps, like :kill, which expands to
  both :kill-tserver and :kill-master. This function expands those."
  [n]
  (cond-> n
    (:kill n) (assoc :kill-tserver true
                     :kill-master true)
    (:stop n) (assoc :stop-tserver true
                     :kill-master true)
    (:pause n) (assoc :pause-master true
                      :pause-tserver true)
    (:partition n) (assoc :partition-one true
                          :partition-half true
                          :partition-ring true)))

(defn nemesis
  "Composite nemesis and generator, given test options."
  [opts]
  (let [n (expand-options (:nemesis opts))]
    {:nemesis         (full-nemesis)
     :generator       (full-generator n)
     :final-generator (final-generator n)}))
