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

;; --- content-addressed memo (ADR-2607310900) -------------------------------

(defn- seeded [n]
  (let [s (local/local-store)]
    (dotimes [i n] (st/-put s "users" (str "u" i) {:name (str "n" i)}))
    s))

(deftest memo-returns-the-same-db-for-the-same-version
  (let [m (bridge/memo) s (seeded 5)
        a (bridge/materialize-memo m s ["users"] "cid-A")
        b (bridge/materialize-memo m s ["users"] "cid-A")]
    (is (identical? a b) "second call is the memoised value, not an equal rebuild")
    (is (= {:size 1 :hits 1 :misses 1} (select-keys (bridge/memo-stats m) [:size :hits :misses])))))

(deftest a-different-version-is-a-different-key-not-a-dirty-entry
  (testing "this is the whole reason the key is a content address: a changed
            graph does not invalidate anything, it simply misses"
    (let [m (bridge/memo) s (seeded 3)
          a (bridge/materialize-memo m s ["users"] "cid-A")]
      (st/-put s "users" "u9" {:name "new"})
      (let [b (bridge/materialize-memo m s ["users"] "cid-B")]
        (is (not (identical? a b)))
        (is (= 4 (count (:spo b))) "the new version sees the write")
        (is (= 3 (count (:spo a))) "and the old value is still the old snapshot")))))

(deftest coll-keys-are-part-of-the-key-and-order-insensitive
  (let [m (bridge/memo) s (seeded 2)]
    (bridge/materialize-memo m s ["users"] "v1")
    (bridge/materialize-memo m s ["users" "other"] "v1")
    (is (= 2 (:size (bridge/memo-stats m))) "different collection sets are different entries")
    (let [a (bridge/materialize-memo m s ["users" "other"] "v1")
          b (bridge/materialize-memo m s ["other" "users"] "v1")]
      (is (identical? a b) "asking for the same collections in another order is the same question"))))

(deftest a-nil-version-is-refused-rather-than-cached-forever
  (testing "defaulting the version would turn this into a cache that never
            invalidates, which is not a cache but a bug returning stale data"
    (is (thrown? #?(:clj AssertionError :cljs js/Error)
                 (bridge/materialize-memo (bridge/memo) (seeded 1) ["users"] nil)))))

(deftest the-memo-is-bounded
  (let [m (bridge/memo 2) s (seeded 1)]
    (doseq [v ["v1" "v2" "v3"]] (bridge/materialize-memo m s ["users"] v))
    (is (= 2 (:size (bridge/memo-stats m))) "capacity is enforced, not aspirational")
    (bridge/materialize-memo m s ["users"] "v1")
    (is (= 4 (:misses (bridge/memo-stats m)))
        "three inserts plus the re-fetch of the evicted v1")))

(deftest materialize-must-never-take-visible
  (testing "the memo is sound ONLY because materialize is visible?-free — one
            db is correct for every caller. If this arity ever grows a
            predicate argument, sharing a db across principals stops being safe
            and this namespace has to be reconsidered, not patched"
    (is (= 2 (apply max (map count (:arglists (meta #'bridge/materialize))))))))

(deftest db-for-needs-both-halves-or-it-builds-fresh
  (testing "a memo with no version has nothing to key on, and a version with no
            memo has nowhere to put the result — neither half alone may silently
            behave as if memoised"
    (let [m (bridge/memo) s (seeded 2)]
      (bridge/db-for {} s ["users"])
      (bridge/db-for {:query-memo m} s ["users"])
      (bridge/db-for {:query-version "v1"} s ["users"])
      (is (= 0 (:size (bridge/memo-stats m))) "nothing was memoised")
      (let [a (bridge/db-for {:query-memo m :query-version "v1"} s ["users"])
            b (bridge/db-for {:query-memo m :query-version "v1"} s ["users"])]
        (is (identical? a b))
        (is (= 1 (:hits (bridge/memo-stats m))))))))

;; --- string document keys ---------------------------------------------------
;; Found in production 2026-07-31: every SPARQL query against kotobase.net
;; returned "internal error: Doesn't support namespace: v2", whatever the query
;; said, while /health answered 200 throughout. The cause was here — documents
;; parsed out of JSON bodies (kotobase.protocols.atproto stores the record
;; verbatim) have STRING keys, and this bridge passed them through as datom
;; attributes. SPARQL's kw->iri-string calls `namespace`, which throws on a
;; string in ClojureScript; Cypher's :text never matched "text" and returned
;; empty. Every test in this repo used keyword keys, so nothing saw it.

(deftest string-doc-keys-become-keyword-attributes
  (let [s (local/local-store)]
    (st/-put s "c" "k" {"text" "hello" "v2" 1})
    (let [db (bridge/materialize s ["c"])
          attrs (into #{} (for [[_ pm] (:spo db) [p _] pm] p))]
      (is (contains? attrs :text))
      (is (contains? attrs :v2))
      (is (every? keyword? attrs)
          "a datom attribute must have one type, whatever the document used"))))

(deftest string-and-keyword-keys-materialize-alike
  (let [a (local/local-store) b (local/local-store)]
    (st/-put a "c" "k" {"name" "Alice" "role" "admin"})
    (st/-put b "c" "k" {:name "Alice" :role "admin"})
    (is (= (:spo (bridge/materialize a ["c"]))
           (:spo (bridge/materialize b ["c"]))))))

(deftest a-string-keyed-doc-is-queryable
  (let [s (local/local-store)]
    (st/-put s "users" "u1" {"name" "Alice" "role" "admin"})
    (st/-put s "users" "u2" {"name" "Bob" "role" "user"})
    (is (= #{["Alice"]}
           (bridge/q (bridge/materialize s ["users"])
                     '{:find [?n] :where [[?e :role "admin"] [?e :name ?n]]}
                     (constantly true))))))

(deftest a-slashed-string-key-round-trips
  ;; (keyword "a/b") splits on the slash; the IRI and Cypher forms both render
  ;; it back as a/b, so the split is not a loss.
  (let [s (local/local-store)]
    (st/-put s "c" "k" {"a/b" 1})
    (let [attrs (into #{} (for [[_ pm] (:spo (bridge/materialize s ["c"])) [p _] pm] p))]
      (is (contains? attrs :a/b))
      (is (= "a" (namespace :a/b)))
      (is (= "b" (name :a/b))))))
