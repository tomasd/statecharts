(ns statecharts.make
  (:require [statecharts.state :as state]
            [statecharts.context :as ctx]
            [statecharts.transition :as transition]
            [clojure.zip :as zip]
            [statecharts.path :as path]))



(defn make-transitions [state state-index]
  (let [{:keys [id transitions]} state]
    (cond-> state
      (some? transitions)
      (update :transitions
              (fn [transitions]
                (->> transitions
                     (mapv (fn [transition]
                             (let [{:keys [target]} transition]
                               (-> transition
                                   (assoc
                                     :source id
                                     :state-index state-index)
                                   (update :target #(path/resolve-path id %)))))))))

      true (transition/new-transition))))

(defn index-states [state]
  (loop [loc (zip/zipper
               (constantly true)
               state/substates
               nil
               state)
         idx {}]
    (if (zip/end? loc)
      idx
      (let [value (zip/node loc)]
        (recur (zip/next loc)
               (assoc idx (:id value) value))))))




(defn make-statechart
  ([state]
   (let [state-index (state/new-index {})
         state       (make-statechart [] (assoc state :id []
                                                      :state-index state-index)
                                      state-index
                                      (volatile! 0))]
     (state/set-index state-index (index-states state))
     state))
  ([path state state-index entry-order]
   (let [order (vswap! entry-order inc)]
     (-> (reduce-kv (fn [state id substate]
                      (assoc-in state [:states id] (make-statechart (conj path id) substate state-index entry-order)))
                    state
                    (:states state))
         (assoc :id path
                :state-index state-index
                :order order)
         (make-transitions state-index)
         (state/new-state)))))



