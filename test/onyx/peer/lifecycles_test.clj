(ns onyx.peer.lifecycles-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [midje.sweet :refer :all]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config]]
            [onyx.api]))

(def id (java.util.UUID/randomUUID))

(def config (load-config))

(def env-config (assoc (:env-config config) :onyx/id id))

(def peer-config (assoc (:peer-config config) :onyx/id id))

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

(def n-messages 100)

(def batch-size 20)

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def counter (atom 0))

(defn start-task? [event lifecycle]
  (swap! counter inc)
  true)

(defn before-task [event lifecycle]
  (swap! counter inc)
  {})

(defn after-task [event lifecycle]
  (swap! counter inc)
  {})

(defn before-batch [event lifecycle]
  (swap! counter inc)
  {})

(defn after-batch [event lifecycle]
  (swap! counter inc)
  {})

(def catalog
  [{:onyx/name :in
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :inc
    :onyx/fn :onyx.peer.lifecycles-test/my-inc
    :onyx/type :function
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Writes segments to a core.async channel"}])

(def workflow [[:in :inc] [:inc :out]])

(def in-chan (chan (inc n-messages)))

(def out-chan (chan (sliding-buffer (inc n-messages))))

(defn inject-in-ch [event lifecycle]
  {:core.async/chan in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan out-chan})

(def calls
  {:lifecycle/start-task? start-task?
   :lifecycle/before-task before-task
   :lifecycle/before-batch before-batch
   :lifecycle/after-batch after-batch
   :lifecycle/after-task after-task})

(def in-calls
  {:lifecycle/before-task inject-in-ch})

(def out-calls
  {:lifecycle/before-task inject-out-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :onyx.peer.lifecycles-test/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :inc
    :lifecycle/calls :onyx.peer.lifecycles-test/calls
    :lifecycle/doc "Test lifecycles that increment a counter in an atom"}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.peer.lifecycles-test/out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(doseq [n (range n-messages)]
  (>!! in-chan {:n n}))

(>!! in-chan :done)
(close! in-chan)

(def v-peers (onyx.api/start-peers 3 peer-group))

(onyx.api/submit-job
 peer-config
 {:catalog catalog
  :workflow workflow
  :lifecycles lifecycles
  :task-scheduler :onyx.task-scheduler/balanced})

(def results (take-segments! out-chan))

(let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
  (fact (set (butlast results)) => expected)
  (fact (last results) => :done))

;; Counter is inc'ed 14 times. 1 time on start up,
;; 5 for each start/stop of a batch, 2 more times
;; for starting and stopping the lifecycle, and 1 more post-batch for
;; shutting down the task.
(fact @counter => 14)

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)
