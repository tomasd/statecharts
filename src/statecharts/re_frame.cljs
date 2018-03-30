(ns statecharts.re-frame
  (:require [statecharts.context :as ctx]
            [statecharts.core :as sc]
            [re-frame.core :as re-frame]))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(def push-event ctx/push-event)
(defn db [ctx]
  (get-in ctx [:fx :db]))

(defn dispatch [ctx event]
  (update-in ctx [:fx :dispatch-n] (fnil conj []) event))

(defn ctx-update-db [f & args]
  (fn [ctx]
    (apply update-in ctx [:fx :db] f args)))

(defn assoc-db-in [ctx path value]
  (assoc-in ctx (into [:fx :db] path) value))

(defn ctx-assoc-db-in [path value]
  (fn [ctx]
    (assoc-db-in ctx path value)))

(defn update-db-in [ctx path f & args]
  (apply update-in ctx (into [:fx :db] path) f args))

(defn get-db-in
  ([ctx path]
   (get-in ctx (into [:fx :db] path)))
  ([ctx path default]
   (get-in ctx (into [:fx :db] path) default)))

(defn ctx-update-db-in [path f & args]
  (fn [ctx]
    (apply update-db-in ctx path f args)))

(defn dissoc-db-in [ctx path]
  (update-in ctx [:fx :db] dissoc-in path))

(defn ctx-dissoc-db-in [path]
  (fn [ctx]
    (dissoc-db-in ctx path)))

(defn fx [ctx fx value]
  (assoc-in ctx [:fx fx] value))

(defn ctx-fx [fx value]
  (fn [ctx _]
    (assoc-in ctx [:fx fx] value)))

(defn conj-fx [ctx fx value]
  (update-in ctx [:fx fx] (fnil conj []) value))

(defn ctx-conj-fx [fx value]
  (fn [ctx _]
    (conj-fx ctx fx value)))

(defn process-event [ctx event]
  (let [configuration (get-in ctx [:db ::configuration])
        statechart    (get-in ctx [:db ::statechart])
        event-ctx     {:configuration configuration
                       :fx            (select-keys ctx [:db])}
        {:keys [fx configuration]} (sc/process-event event-ctx statechart event)]
    (assoc-in fx [:db ::configuration] configuration)))

(defn reg-event [event]
  (re-frame/reg-event-fx event
                         ;[re-frame/debug]
                         (fn [ctx event]
                           (process-event ctx event))))

(defn initialize [fx statechart]
  (let [{:keys [fx configuration]} (sc/initialize fx statechart)]
    (doseq [event (->> statechart
                       (tree-seq (constantly true) (comp vals :states))
                       (mapcat :transitions)
                       (map :event)
                       distinct)]
      (reg-event event))
    (-> fx
        (assoc-in [:db ::configuration] configuration)
        (assoc-in [:db ::statechart] statechart))))