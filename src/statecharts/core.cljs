(ns statecharts.core
  (:require
    [statecharts.state :as state]
    [statecharts.context :as ctx]
    [statecharts.transition :as transition]
    [statecharts.runtime :refer [run enter-states]]
    [statecharts.make :as make]))

(defn make [statechart-definition]
  (make/make-statechart statechart-definition))

(defn- make-result [ctx]
  {:fx            (:fx ctx)
   :configuration {:configuration (->> (ctx/full-configuration ctx)
                                       (filter state/atomic?)
                                       (sort-by state/entry-order)
                                       (map :id)
                                       (into []))
                   :history       (:history ctx)}})
(defn process-event [ctx statechart event]
  (let [ctx (loop [ctx (ctx/init-ctx ctx statechart event)]
              (if (seq (:internal-queue ctx))
                (let [ctx (run ctx)]
                  (recur ctx))
                ctx))]
    (make-result ctx)))


(defn initialize [fx statechart]
  (let [states (->> (state/initialize-statechart statechart)
                    (mapcat #(cons % (state/proper-ancestors % nil)))
                    (distinct))
        ctx    (enter-states (ctx/init-ctx {:configuration #{}
                                            :fx            fx} statechart) states)]
    (make-result ctx)))