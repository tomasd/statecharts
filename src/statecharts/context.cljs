(ns statecharts.context
  (:require [statecharts.state :as state]))

(defn queue []
  #queue []
  ;#_#?(:clj  clojure.lang.PersistentQueue/EMPTY
  ;   :cljs #queue [])
  )

(defn current-event [ctx]
  (:current-event ctx))

(defn current-event-id [ctx]
  (first (current-event ctx)))

(defn pop-event [ctx]
  (let [event (-> ctx :internal-queue peek)]
    (-> ctx
        (assoc :current-event event)
        (update :internal-queue pop))))

(defn push-event [ctx event]
  (-> ctx
      (update :internal-queue conj event)))

(defn init-ctx
  ([ctx statechart]
   (let [{{:keys [configuration history]} :configuration} ctx]
     (-> ctx
         (assoc :internal-queue (queue))
         (assoc :configuration (->> (or configuration #{})
                                    (map #(state/get-state statechart %))
                                    (filter state/atomic?)
                                    (into #{})))
         (assoc :history (or history {})))))
  ([ctx statechart event]
   (-> (init-ctx ctx statechart)
       (update :internal-queue conj event))))

(defn current-configuration [ctx]
  (get-in ctx [:configuration]))

(defn full-configuration [ctx]
  (->> (current-configuration ctx)
       (mapcat #(cons % (state/proper-ancestors % nil)))
       (distinct)))

(defn state-history [state ctx]
  (let [value (->> (get-in ctx [:history (state/state-id state)])
                   (map #(state/get-state state %)))]
    (if-not (seq value)
      [state]
      value)))

(defn save-history [ctx exit-states]
  (reduce (fn [ctx state]
            (cond-> ctx
              (state/shallow-history? state)
              (assoc-in [:history (state/state-id state)]
                        (->> (full-configuration ctx)
                             (filter #(state/child? % state))
                             (map :id)))

              (state/deep-history? state)
              (assoc-in [:history (state/state-id state)]
                        (->> (full-configuration ctx)
                             (filter #(state/descendant? % state))
                             (filter state/atomic?)
                             (map :id)))))
          ctx
          exit-states))