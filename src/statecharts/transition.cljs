(ns statecharts.transition
  (:require [statecharts.state :as state]
            [statecharts.context :as ctx])
  )


(defrecord Transition [event source target internal execute state-index])
(defn new-transition [val]
  val)

(defn trigger-event [transition]
  (:event transition))

(defn target-state [transition]
  (state/get-state transition (:target transition)))

(defn source-state [transition]
  (state/get-state transition (:source transition)))

(defn internal? [transition]
  (:internal transition false))

(defn on-trigger [transition]
  (:execute transition))

(defn applicable-transition? [transition ctx]
  (let [event (ctx/current-event ctx)
        event-id (first event)
        {:keys [condition]} transition
        trigger-event' (trigger-event transition)]
    (and (or (nil? trigger-event')
             (= event-id trigger-event'))
         (or (nil? condition)
             (condition ctx event)))))

(defn targetted-transition? [transition]
  (some? (target-state transition)))

