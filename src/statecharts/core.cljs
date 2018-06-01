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

(defn- process-ctx [ctx]
  (loop [ctx ctx]
    (if (seq (:internal-queue ctx))
      (let [ctx (run ctx)]
        (recur ctx))
      ctx)))
(defn process-event [ctx statechart event]
  (let [ctx (-> (ctx/init-ctx ctx statechart event)
                (process-ctx))]
    (make-result ctx)))


(defn initialize [fx statechart]
  (let [states (->> (state/initialize-statechart statechart)
                    (mapcat #(cons % (state/proper-ancestors % nil)))
                    (distinct))
        ctx    (-> (ctx/init-ctx {:configuration #{}
                                  :fx            fx} statechart)
                   (enter-states states)
                   (process-ctx))]
    (make-result ctx)))