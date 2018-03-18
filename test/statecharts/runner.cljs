(ns statecharts.runner
  (:require [cljs.test :as test]
            [doo.runner :refer-macros [doo-all-tests]]
            [re-graph.all-tests]))

(doo-all-tests #"^statecharts.*-test$")