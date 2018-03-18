# statecharts

statecharts is a scxml implementation for ClojureScript with bindings for [re-frame](https://github.com/Day8/re-frame) applications.

## Notes


Features include:
* Works with immutable structures
* Seamless integration with `re-frame`
* All effects are described as datastructures. 
* `re-frame` effects can be directly used  

## Usage

Add statecharts to your project's dependencies:

[![Clojars Project](https://img.shields.io/clojars/v/statecharts.svg)](https://clojars.org/statecharts)

### Example usage

Imports:
```clojure
(require [re-frame.core :as re-frame]
         [statecharts.re-frame :as re-frame-sc]
         [statecharts.core :as sc])
         
 (defn assoc-page [page]
   (re-frame-sc/ctx-assoc-db-in [:page] page))
   
(defn farg=? [expected-page]
 (fn [ctx [_ page]]
   (= page expected-page)))


```

#### Anonymous pages
Define anonymous pages. 
```clojure
(def anonymous-pages
  {:type        :xor
   :init        :login-screen
   :states      {:login-screen {:enter [(assoc-page :page/login-screen)]}}
   :transitions [{:event :login}]})
```

There is only 1 anonymous page in this state: `login-screen`. This is also initial state. Uppon enter into this state, `:page/login-screen` is assoced into re-frame database.

#### Authenticated pages
```clojure
(def authenticated-pages
  {:type        :xor
   :init        :index
   :states      {:dashboard  {:enter [(assoc-page :page/dashboard)]}
                 :users-list {:enter [(assoc-page :page/users-list)]}}
   :transitions [{:event  :logout
                  :target (path/parent 2 [:anonymous])}
                 {:event     :goto-page
                  :condition (farg=? :dashboard)
                  :target    :dashboard}
                 {:event     :show-users
                  :condition (farg=? :users-list)
                  :target    :users-list}]})
```
This state machine is of type `:xor`. `:xor` defines that machine is in one of the specified states. There exists also type `:and`, where machine is in all defined substates. This is useful for orthogonal machines like websocket connection and current page.

There are 2 authenticated pages defined in this state machine:
* Dashboard
* Users list

Each page sets correct value into the database uppon enter. You can use this value for showing current page view in re-frame. 

There are also 2 events registered for authenticated pages:
* `logout`
* `goto-page`

`goto-page` is defined multiple times, each time with different condition. In these example we are checking 1st argument of the re-frame event (e.g. `:condition (farg=? :users-list)`).

You can define transition's target as:
* keyword - direct substate e.g. `:dashboard`
* vector - absolute path to state, starting from root state e.g. `[:authenticated :dashboard]`
* `statecharts.path/child` - relative path to state, starting from this state e.g. `(path/child [:dashboard])`
* `statecharts.path/parent` - like child but starting from parent state e.g. `(path/parent [:dashboard])` 

#### Top level states
```clojure
(re-frame/reg-event-fx
  :load-user-data
  (fn [_ _]
    ; check current user with current cookies
    (ajax/post "/get-user"
               (fn [data]
                 (if (some? data)
                   (re-frame/dispatch [:login-successful data])
                   (re-frame/dispatch [:login-failed]))))))
```
Register `re-frame` handler checking for current user data. User is for instance in cookes and ajax call will return current user.

```clojure
(def statechart
  (sc/make {:type   :xor
            :init   :undefined
            :states {:undefined     {:enter       [(fn [ctx]
                                                     ; make ajax call, by calling :load-user-data event-fx handler
                                                     ; on success dispatch re-frame event :login-successful
                                                     ; on failure dispatch re-frame event :login-failed
                                                     (re-frame-sc/dispatch ctx [:load-user-data]))]
                                     :transitions [{:event   :login-successful
                                                    :execute [(fn [ctx [_ user]]
                                                                (re-frame-sc/assoc-db-in [:user] user))]
                                                    :target  (path/parent [:authenticated])}
                                                   {:event  :login-failed
                                                    :target (path/parent [:anonymous])}]}
                     :anonymous     anonymous-pages
                     :authenticated authenticated-pages}}))
```
This is top level statechart. It should be processed with `statecharts.core/make`, which will do some basic processing like indexing and so...

This example is `:xor` machine with 3 substates:
* `:undefined` - initial state fires `:load-user-data`. This state is not used after `:login-successful`/`:login/failed` events. These events are processed and assoc values into db.
* `:anonymous` - for anonymous user
* `:authenticated` - for authenticated user

#### Re-frame initializaion
```clojure
                  
(re-frame/reg-event-fx
  :initialize-db
  (fn [_ _]
    (-> {:db                    db/default-db}
        (re-frame-sc/initialize statechart))))
```

`statecharts.re-frame/initialize` will register all transitions as `re-frame` event handlers.


#### Example react application
```clojure
(re-frame/reg-sub
  :current-page
  (fn [db _]
    (:page db)))

(defn application []
  (case @(re-frame/subscribe [:current-page])
    :page/login-screen
    [login-screen]

    :page/dashboard
    [dashboard]

    :page/users-list
    [users-list]))
```

Statecharts will adjust/cleanup database correctly. In rect/reagent we need just to display DB.

## Reference

### Statecharts context

Statecharts context is basically just wrapped re-frame effects map with following structure:
```clojure
{:fx {:db CURRENT_REFRAME_DB }}
```

`[:fx :db]` effect is always initialized witch current value from db (`cofx db`)

`:fx` is passed to re-frame for handling side effects.

### Handler
```clojure
(fn [ctx current-event] ctx)
```

Handler is function of 2 arguments. It can be used in state's `enter`/`exit` hooks and transitions `execute` hook. 

All hooks are always vectors of handles, so it's easy to add new processing handlers for instance for enriching state machines with decorator functions. 

### Transition event
```clojure
{:event :my-event
 :condition (fn [ctx current-event] boolean)
 :execute [handlers]
 :internal true/false
 :target PATH
}
```

All values are optional. Transition without `event` is called eventless and is always triggered as first.

`event` will match re-frame's event vector first element. 

Internal transition will not leave current state (default behavior) only just enters children states.

If `target` is not specified transition is basically kind of callback where `execute` handlers are executed for possible side-effects.

If `condition` function is specified this transition is executed only when true is returned from condition function. 
### State definition

#### Simple state
```clojure
{:enter [handlers...]
 :exit [handlers...]
 :transitions [event definitions...]}
```

#### Compound state
```clojure
{:type :xor
 :init INIT_STATE
 :states {INIT_STATE state
          :other-state state}
 :enter [handlers...]
 :exit [handlers...]
 :transitions [transition events definitions...]}
```

#### Component state
```clojure
{:type :and
 :states {:state1 state
          :state2 state}
 :enter [handlers...]
 :exit [handlers...]
 :transitions [event definitions...]}
```

### API

#### Initialization
`statecharts.core/make` - Convert clojure datastructures into statechart. Indexes states.

`statecharts.re-frame/initialize` - Initialize re-frame db with statecharts. Usage:
```clojure
(def statechart (sc/make {}))

(re-frame/reg-event-fx
  :initialize-db
  (fn [_ _]
    (-> {:db                    db/default-db}
        (re-frame-sc/initialize statechart))))
```


#### Target path
`statecharts.path/this` - this

`statecharts.path/parent` - relative path from parent

`statecharts.path/child` - relative path from this

`statecharts.path/sibling` - shortcut for (sibling :other) = (parent [:other])


#### Event processing
`statecharts.re-frame/dispatch` - Registers new event into re-frame fx inside `dispatch-n` effect.

`statecharts.re-frame/push-event` - Registers new event into statecharts internal queue. This event will be processed immediately within current re-frame handler.


#### Re-frame db access
`statecharts.re-frame/db` - get current db from statecharts ctx.

`statecharts.re-frame/get-db-in` - get path from current db

`statecharts.re-frame/ctx-assoc-db-in` - create handler which will assoc value into db

`statecharts.re-frame/ctx-update-db-in` - create handler which will update value in db

`statecharts.re-frame/ctx-dissoc-db-in` - create handler which will dissoc value on path in db

`statecharts.re-frame/assoc-db-in` - assoc value into current db

`statecharts.re-frame/update-db-in` - update value in current db

`statecharts.re-frame/dissoc-db-in` - remove value on path from current db


## Development

```clojure
user> (dev)
dev> (start)
;; visit http://localhost:3449/devcards/index.html
dev> (cljs)
cljs.user>
```

[![CircleCI](https://circleci.com/gh/oliyh/statecharts.svg?style=svg)](https://circleci.com/gh/oliyh/statecharts)

## License

Copyright © 2018 Tomas Drencak

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.