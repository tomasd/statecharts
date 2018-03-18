(ns statecharts.state)

(defprotocol IStatechartIndex
  (set-index [this idx]))
(deftype StatechartIndex [^:volatile-mutable idx]
  IStatechartIndex
  (set-index [this index]
    (set! idx index))

  cljs.core/ILookup
  (-lookup [o k]
    (get idx k))
  (-lookup [o k not-found]
    (get idx k not-found))
  ;#_#?@(:clj
  ;    [clojure.lang.ILookup
  ;     (valAt [o k]
  ;       (get idx k))
  ;     (valAt [o k not-found]
  ;       (get idx k not-found))]
  ;    :cljs
  ;    [cljs.core/ILookup
  ;     (-lookup [o k]
  ;              (get idx k))
  ;     (-lookup [o k not-found]
  ;              (get idx k not-found))])
  )
(defn new-index [val]
  (StatechartIndex. val))
(defrecord State [id states type transitions order exit enter state-index])
(defn new-state [val]
  val #_(map->State val))

(defn initialize-statechart [statechart]
  (case (:type statechart)
    :and
    (mapcat initialize-statechart (vals (:states statechart)))

    :xor
    (initialize-statechart (get-in statechart [:states (:init statechart)]))

    [statechart]))
(defn initial-states [statechart]
  (case (:type statechart)
    :xor
    (list (get-in statechart [:states (:init statechart)]))

    :and
    (vals (:states statechart))

    (list)))
(defn substates [statechart]
  (vals (:states statechart)))
(defn state-id [statechart]
  (:id statechart))
(defn state-type [statechart]
  (:type statechart))
(defn state-transitions [statechart]
  (:transitions statechart))
(defn entry-order [statechart]
  (:order statechart))
(defn on-exit [statechart]
  (:exit statechart))
(defn on-enter [statechart]
  (:enter statechart))
(defn get-state [statechart id]
  (get (:state-index statechart) id))
(defn deep-history? [statechart]
  (= (:history statechart) :deep))
(defn shallow-history? [statechart]
  (= (:history statechart) :shallow))
(defn history? [statechart]
  (or (deep-history? statechart)
      (shallow-history? statechart)))

(defn compound? [state]
  (= (state-type state) :xor))

(defn component? [state]
  (= (state-type state) :and))

(defn atomic? [state]
  (not (or (compound? state)
           (component? state))))

(defn parent-id [state]
  (let [id (state-id state)]
    (cond
      (= id []) nil
      (= (count id) 1) []

      :else (->> id
                 (butlast)
                 vec))))

(defn parent-state [state]
  (let [{:keys [state-index]} state]
    (get state-index (parent-id state))))

(defn descendant? [child parent]
  (let [parent-id (when parent
                    (state-id parent))
        child-id  (state-id child)]
    (and
      (< (count parent-id) (count child-id))
      (= (->> (map vector child-id parent-id)
              (take-while #(apply = %))
              (map first))
         parent-id))))

(defn child-id? [child-id parent-id]
  (and
    (= (count parent-id) (dec (count child-id)))
    (= parent-id (butlast child-id))))

(defn child? [child parent]
  (let [parent-id (when parent
                    (state-id parent))
        child-id  (state-id child)]
    (and
      (= (count parent-id) (dec (count child-id)))
      (= parent-id (butlast child-id)))))

(defn proper-ancestors [state1 cap-state]
  (let [state1-id (state-id state1)
        state2-id (when (some? cap-state)
                    (state-id cap-state))]
    (->> (range 0 (count state1-id))
         (map #(subvec state1-id 0 %))
         (reverse)
         (take-while #(not= % state2-id))
         (map #(get-state state1 %)))))