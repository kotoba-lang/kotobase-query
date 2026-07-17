(ns kotobase.query.bridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [arrangement.core :as arr]
            [kotobase.local :as local]
            [kotobase.store :as st]
            [kotobase.query.bridge :as bridge]))

(def ^:private everything (constantly true))

(defn- fixture-store
  "A LocalStore with two collections: `users` (several docs, one with a
  multi-valued `:tags` attribute, one with no `:dept-key` at all) and
  `departments` (the join target). Mirrors a realistic small dataset a
  consumer repo (datomic-client-shim etc.) would materialize and query."
  []
  (let [s (local/local-store)]
    (st/-put s "users" "u1" {:name "Alice" :role "admin" :dept-key "d1"
                              :tags ["eng" "lead"]})
    (st/-put s "users" "u2" {:name "Bob" :role "user" :dept-key "d2"})
    (st/-put s "users" "u3" {:name "Carol" :role "admin" :dept-key "d1"})
    (st/-put s "users" "u4" {:name "Dave" :role "user"}) ; no dept-key
    (st/-put s "departments" "d1" {:name "Engineering" :budget 900000})
    (st/-put s "departments" "d2" {:name "Sales" :budget 400000})
    s))

;; ------------------------------------------------------------- materialize

(deftest materialize-produces-one-entity-per-doc-key
  (let [db (bridge/materialize (fixture-store) ["users" "departments"])]
    (is (= #{:users/u1 :users/u2 :users/u3 :users/u4
             :departments/d1 :departments/d2}
           (into #{} (keys (:spo db))))
        "one entity, keyword `coll/key`, per materialized doc")))

(deftest materialize-maps-doc-attrs-and-synthetic-attrs
  (let [db (bridge/materialize (fixture-store) ["users"])]
    (is (= {:name #{"Alice"} :role #{"admin"} :dept-key #{"d1"}
            :tags #{"eng" "lead"}
            :kotobase/coll #{"users"} :kotobase/key #{"u1"}}
          (arr/entity-attrs db :users/u1))
        "top-level doc keys become attrs; :tags (a vector) becomes a
        cardinality-many attribute (one datom per element, unioned into a
        set on read-back); :kotobase/coll and :kotobase/key are always
        present")))

(deftest materialize-non-map-doc-falls-back-to-kotobase-value
  (let [s (local/local-store)]
    (st/-put s "counters" "c1" 42)
    (let [db (bridge/materialize s ["counters"])]
      (is (= {:kotobase/value #{42} :kotobase/coll #{"counters"} :kotobase/key #{"c1"}}
             (arr/entity-attrs db :counters/c1))))))

(deftest materialize-combines-multiple-collections-into-one-db
  (let [db (bridge/materialize (fixture-store) ["users" "departments"])]
    (is (contains? (:spo db) :users/u1))
    (is (contains? (:spo db) :departments/d1))))

;; -------------------------------------------------------------------- q / query

(deftest equality-filter-single-var
  (let [db (bridge/materialize (fixture-store) ["users"])
        result (bridge/q db '{:find [?name] :where [[?u :role "admin"] [?u :name ?name]]}
                          everything)]
    (is (= #{["Alice"] ["Carol"]} result)
        "only admins come back, by exact-value equality filter on :role")))

(deftest multiple-find-vars
  (let [db (bridge/materialize (fixture-store) ["users"])
        result (bridge/q db '{:find [?u ?name] :where [[?u :role "admin"] [?u :name ?name]]}
                          everything)]
    (is (= #{[:users/u1 "Alice"] [:users/u3 "Carol"]} result)
        "both the entity and the projected attribute come back when :find
        lists more than one variable")))

(deftest multi-valued-attribute-produces-one-row-per-value
  (let [db (bridge/materialize (fixture-store) ["users"])
        result (bridge/q db '{:find [?tag] :where [[?u :kotobase/key "u1"] [?u :tags ?tag]]}
                          everything)]
    (is (= #{["eng"] ["lead"]} result))))

(deftest join-across-two-materialized-collections
  (testing "a user's :dept-key value joins against the department entity's
    :kotobase/key attribute -- a real cross-collection join, not a single
    collection's [e a v] pattern"
    (let [db (bridge/materialize (fixture-store) ["users" "departments"])
          result (bridge/q db
                            '{:find [?uname ?dname]
                              :where [[?u :name ?uname]
                                      [?u :dept-key ?dk]
                                      [?d :kotobase/coll "departments"]
                                      [?d :kotobase/key ?dk]
                                      [?d :name ?dname]]}
                            everything)]
      (is (= #{["Alice" "Engineering"] ["Bob" "Sales"] ["Carol" "Engineering"]}
             result)
          "Dave (no :dept-key) correctly drops out of the join -- an
          unbound clause fails to match, it does not produce a nil row"))))

(deftest join-with-aggregate-and-equality-filter-combined
  (testing "join, then count grouped by department name -- exercises
    arrangement.datalog's group-by aggregate through this bridge, not just
    a bare join"
    (let [db (bridge/materialize (fixture-store) ["users" "departments"])
          result (bridge/q db
                            '{:find [?dname (count ?u)]
                              :where [[?u :dept-key ?dk]
                                      [?d :kotobase/key ?dk]
                                      [?d :name ?dname]]}
                            everything)]
      (is (= #{["Engineering" 2] ["Sales" 1]} result)))))

(deftest query-convenience-fn-equals-materialize-then-q
  (let [store (fixture-store)
        via-query (bridge/query store ["users" "departments"]
                                 '{:find [?uname ?dname]
                                   :where [[?u :name ?uname]
                                           [?u :dept-key ?dk]
                                           [?d :kotobase/key ?dk]
                                           [?d :name ?dname]]}
                                 everything)
        via-materialize-then-q (bridge/q (bridge/materialize store ["users" "departments"])
                                          '{:find [?uname ?dname]
                                            :where [[?u :name ?uname]
                                                    [?u :dept-key ?dk]
                                                    [?d :kotobase/key ?dk]
                                                    [?d :name ?dname]]}
                                          everything)]
    (is (= via-materialize-then-q via-query)
        "the one-shot convenience fn is exactly materialize+q, no extra behavior")))

(deftest visible-filters-out-redacted-entities
  (let [db (bridge/materialize (fixture-store) ["users"])
        no-bob? (fn [{:keys [s]}] (not= s :users/u2))
        result (bridge/q db '{:find [?name] :where [[?u :name ?name]]} no-bob?)]
    (is (not (contains? result ["Bob"])))
    (is (contains? result ["Alice"]))))

(deftest visible-is-required-not-defaulted
  (is (thrown? #?(:clj clojure.lang.ArityException :cljs js/Error)
               #_:clj-kondo/ignore ; deliberately wrong arity -- that's the point of this test
               (bridge/q (bridge/materialize (fixture-store) ["users"])
                         '{:find [?name] :where [[?u :name ?name]]}))
      "q refuses to run with no stated visibility decision, same discipline
      as arrangement.datalog/q and the retired kqe (ADR-2607050500)"))

(deftest in-clause-inputs-are-threaded-through
  (let [db (bridge/materialize (fixture-store) ["users"])
        result (bridge/q db '{:find [?name] :in [?role] :where [[?u :role ?role] [?u :name ?name]]}
                          everything ["admin"])]
    (is (= #{["Alice"] ["Carol"]} result))))
