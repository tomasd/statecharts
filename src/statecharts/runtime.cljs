(ns statecharts.runtime
  (:require [statecharts.state :as state]
            [statecharts.context :as ctx]
            [statecharts.transition :as transition]
            [clojure.set :as set]
            [re-frame.trace :as trace :include-macros true]))

(defn select-event-transitions [state event]
  (concat (->> (state/state-transitions state)
               (filter #(= (transition/trigger-event %) event)))
          (lazy-seq (if-some [parent (state/parent-state state)]
                      (select-event-transitions parent event)))))

(defn select-eventless-transitions [state]
  (concat (->> (state/state-transitions state)
               (filter #(nil? (transition/trigger-event %))))
          (lazy-seq (if-some [parent (state/parent-state state)]
                      (select-eventless-transitions parent)))))


(defn effective-target-states [transition ctx]
  ; TODO Add history
  (->> (list (transition/target-state transition))
       (mapcat (fn [target]
                 (if (state/history? target)
                   (ctx/state-history target ctx)
                   [target])))))


(defn find-lcca [states]
  (let [[head & tail] states]
    (->> (state/proper-ancestors head nil)
         (filter #(or (state/compound? %) (= (:id %) [])))
         (filter (fn [ancestor]
                   (every? #(state/descendant? % ancestor) tail)))
         first)))

(defn transition-domain [t ctx]
  (let [states (effective-target-states t ctx)
        source (transition/source-state t)]
    (cond

      (empty? states)
      nil

      (and
        (transition/internal? t)
        (state/compound? source)
        (not (state/atomic? source))
        (->> states
             (every? #(state/descendant? % source))))
      source

      :else
      (find-lcca (cons source states)))))



(defn transitions-exit-set [ctx enabled-transitions]
  (let [configuration (ctx/full-configuration ctx)]
    (->> enabled-transitions
         (filter transition/targetted-transition?)
         (mapcat (fn [t]
                   (let [domain (transition-domain t ctx)]
                     (->> configuration
                          (filter #(state/descendant? % domain))))))
         distinct)))

(declare add-descendants')
(declare add-ancestors')


(defn add-ancestors' [state ancestor ctx acc]
  (->> (state/proper-ancestors state ancestor)
       (reduce (fn [acc ancestor]
                 (if (contains? acc ancestor)
                   acc
                   (if (state/component? ancestor)
                     (->> (state/substates ancestor)
                          (reduce
                            (fn [acc sub-state]
                              (if (contains? acc sub-state)
                                acc
                                (->> acc
                                     (add-descendants' sub-state ctx))))
                            (conj acc ancestor)))
                     (conj acc ancestor))))
               acc)))



(defn add-descendants' [state ctx acc]
  (if (state/history? state)
    (->> (ctx/state-history state ctx)
         (reduce (fn [acc state]
                   (->> (state/initial-states state)
                        (reduce
                          (fn [acc init-state]
                            (if (contains? acc init-state)
                              acc
                              (->> acc
                                   (add-descendants' init-state ctx)
                                   (add-ancestors' init-state state ctx))))
                          (conj acc state))))
                 (conj acc state)
                 ))

    (cond
      (state/compound? state)
      (->> (state/initial-states state)
           (reduce
             (fn [acc init-state]
               (if (contains? acc init-state)
                 acc
                 (->> acc
                      (add-descendants' init-state ctx)
                      (add-ancestors' init-state state ctx))))
             (conj acc state)))

      (state/component? state)
      (->> (state/initial-states state)
           (reduce
             (fn [acc init-state]
               (if (contains? acc init-state)
                 acc
                 (->> acc
                      (add-descendants' init-state ctx))))
             (conj acc state)))

      :else
      (conj acc state))))


(defn transitions-entry-set [ctx transitions]
  (->> transitions
       (filter transition/targetted-transition?)
       (mapcat (fn [t]
                 (let [ancestor               (transition-domain t ctx)
                       acc                    #{}
                       acc                    (add-descendants' (transition/target-state t) ctx acc)
                       acc                    (->> (effective-target-states t ctx)
                                                   (reduce (fn [acc s]
                                                             (add-ancestors' s ancestor ctx acc))
                                                           acc))
                       ; TODO Commented out as it entered wrong init states in :xor state
                       ;acc                    (->> other-children
                       ;                            (reduce (fn [acc s]
                       ;                                      (add-descendants' s ctx acc))
                       ;                                    acc))
                       acc                    (reduce (fn [acc s]
                                                        (add-ancestors' s ancestor ctx acc))
                                                      acc
                                                      (effective-target-states t ctx)
                                                      )
                       states                 acc]
                   states)))
       (distinct)))



(defn update-configuration [ctx f & args]
  (-> (apply update-in ctx [:configuration] f args)
      (update-in [:configuration] #(->> %
                                        (filter state/atomic?)
                                        (into #{})))))


(defn invoke-executions [ctx executions]
  (let [event (ctx/current-event ctx)]
    (reduce (fn [ctx execution]
              (execution ctx event))
            ctx
            executions)))

(defn exit-transitions-states [ctx enabled-transitions]
  (let [states-to-exit  (transitions-exit-set ctx enabled-transitions)
        exit-executions (->> states-to-exit
                             (sort-by state/entry-order)
                             (mapcat state/on-exit))]
    (if (trace/is-trace-enabled?)
      (doseq [state states-to-exit]
        (trace/with-trace {:op-type   :sc/exit
                           :operation (pr-str (:id state))
                           :tags      {:id    (:id state)
                                       :state state}})))
    (-> ctx
        (ctx/save-history states-to-exit)
        (update-configuration clojure.set/difference (into #{} states-to-exit))
        (invoke-executions exit-executions))))

(defn enter-states [ctx states]
  (->> states
       ; sort by entry order = document order
       (sort-by state/entry-order)
       (reduce (fn [ctx state]
                 (trace/with-trace
                   {:op-type   :sc/enter
                    :operation (pr-str (:id state))
                    :tags      {:id    (:id state)
                                :state state}}
                   (-> ctx
                       (update-configuration (fnil conj #{}) state)
                       (invoke-executions (state/on-enter state)))))
               ctx)))

(defn enter-transition-states [ctx enabled-transitions]
  (let [states-to-enter (transitions-entry-set ctx enabled-transitions)]
    (->> states-to-enter
         (enter-states ctx))))

(defn execute-transitions [ctx enabled-transitions]
  (let [executions (->> enabled-transitions
                        (mapcat transition/on-trigger))]
    (invoke-executions ctx executions)))

(defn microstep [ctx enabled-transitions]
  (-> ctx
      (exit-transitions-states enabled-transitions)
      (execute-transitions enabled-transitions)
      (enter-transition-states enabled-transitions)))



(defn xf-conflicting-transitions [ctx]
  (fn [xf]
    (let [filtered-transitions (volatile! #{})]
      (fn
        ([]
         (xf))
        ([acc]
         (->> @filtered-transitions
              (reduce xf acc)
              (xf)))
        ([acc t1]
         (let [transitions-to-remove (loop [filtered-transitions  @filtered-transitions
                                            transitions-to-remove #{}]
                                       (if-not (empty? filtered-transitions)
                                         (let [t2 (first filtered-transitions)]
                                           (if-not (empty? (clojure.set/intersection (into #{} (transitions-exit-set ctx [t1]))
                                                                                     (into #{} (transitions-exit-set ctx [t2]))))
                                             (if (state/descendant? (transition/source-state t1) (transition/source-state t2))
                                               (recur (rest filtered-transitions)
                                                      (conj transitions-to-remove t2))
                                               :preempted)))
                                         transitions-to-remove))]
           (if-not (= transitions-to-remove :preempted)
             (vswap! filtered-transitions #(-> %
                                               (clojure.set/difference transitions-to-remove)
                                               (conj t1))))
           acc))))))

(defn remove-conflicting-transitions [ctx transitions]
  (into [] (xf-conflicting-transitions ctx) transitions))

(defn select-transitions [ctx select-transitions]
  (->> (ctx/current-configuration ctx)
       (filter state/atomic?)
       (sort-by state/entry-order)
       (map (fn [atomic-state]
              (->> (select-transitions atomic-state)
                   (filter #(transition/applicable-transition? % ctx))
                   first)))
       (remove nil?)
       distinct
       (remove-conflicting-transitions ctx)
       ))

(defn eventless-transitions [ctx]
  (select-transitions ctx select-eventless-transitions))
(defn event-transitions [ctx]
  (select-transitions ctx #(select-event-transitions % (ctx/current-event-id ctx))))


(defn run [ctx]
  (loop [ctx (ctx/pop-event ctx)]
    (let [enabled-transitions (eventless-transitions ctx)]
      (cond
        (not (empty? enabled-transitions))
        (recur (trace/with-trace
                 {:op-type   :sc/microstep
                  :operation :eventless
                  :tags      {:event (ctx/current-event ctx)
                              :enabled-transitions enabled-transitions}}
                 (microstep ctx enabled-transitions)))

        :else
        (let [transitions (event-transitions ctx)]
          (trace/with-trace
            {:op-type   :sc/microstep
             :operation (first (ctx/current-event ctx))
             :tags      {:event (ctx/current-event ctx)
                         :enabled-transitions transitions}}
            (microstep ctx transitions)))))))