(ns kotobase.query.bridge
  "The kotobase-query bridge (ADR-2607172300 in `com-junkawasaki/root`) --
  materializes `kotobase.store/IStore` collections as datoms and runs
  Datomic-shaped `:find`/`:where` Datalog queries over them via
  `arrangement.datalog`.

  **Why this repo exists**: `kotobase.store`/`kotobase.local` (`IStore`) is
  a flat get/put/list(keys-in-one-collection)/append store -- no joins, no
  predicates across collections, no query language. `kotoba-lang/arrangement`
  is a 4-covering triple index (`spo`/`pso`/`pos`/`ocp` ≡ Datomic's
  EAVT/AEVT/AVET/VAET) with a real Datalog engine (`arrangement.datalog/q`)
  over it, but nothing wires `IStore` documents INTO that index. This
  namespace is that wiring -- a thin bridge, not a reimplementation:
  `arrangement.datalog` does all the actual query evaluation.

  **v0.1 scope / limitation, read before depending on this** -- linear
  materialization: `materialize` does a full `-list` + `-get` scan of every
  requested collection on every call, builds a throwaway in-memory
  `arrangement.core` db, and discards it. No incremental indexing, no
  caching, no persistence, no `arrangement.core/commit!` (the CID-addressed
  snapshot machinery is untouched -- this bridge never calls it). This is a
  deliberate, accepted v0.1 limitation (ADR-2607172300): fine for the small/
  test-scale query volume this bridge is built for right now; real
  incremental indexing via `arrangement`'s own index maintenance is a
  documented follow-up once there is real query-volume evidence, not a v0.1
  requirement.

  ## Doc -> datoms mapping

  For a document `doc` stored at `(-get store coll k)`:

  - the entity (`:s`) is `(keyword (str coll) (str k))` -- e.g. collection
    `\"users\"` key `\"u1\"` becomes the entity `:users/u1`. Globally unique
    across every materialized collection (no collision risk from bare keys
    repeating across collections), and human-readable in query results.
  - every entity ALWAYS gets two synthetic attributes so collection/key
    identity survives materialization and so cross-collection joins have
    something concrete to join ON: `:kotobase/coll` -> `(str coll)` and
    `:kotobase/key` -> `(str k)`. A doc in another collection that stores a
    foreign-key-shaped value (e.g. `{:dept-key \"d1\"}`) joins against the
    referenced entity via its `:kotobase/key` attribute -- see the worked
    join example below and in `test/kotobase/query/bridge_test.cljc`.
  - if `doc` is a map: every top-level `[attr v]` pair becomes a datom
    `{:s entity :p attr :o v}` -- `attr` is used AS-IS (whatever key shape
    the document actually used, typically a keyword; not coerced to a
    string). If `v` is itself a non-map collection (vector/set/list), it is
    treated as a Datomic-style cardinality-many attribute: one datom per
    element (`{:s entity :p attr :o el}` for each `el`), not one datom
    whose `:o` is the whole collection.
  - if `doc` is not a map (a bare scalar, or `nil`): one fallback datom
    `{:s entity :p :kotobase/value :o doc}`.
  - nested maps as attribute values are stored as an opaque `:o` value
    (not recursively flattened into their own entity) -- out of scope for
    v0.1, a documented limitation, not silently mishandled.

  ## Worked example

  ```clojure
  (require '[kotobase.local :as local]
           '[kotobase.store :as st]
           '[kotobase.query.bridge :as bridge])

  (def store (local/local-store))
  (st/-put store \"users\" \"u1\" {:name \"Alice\" :dept-key \"d1\"})
  (st/-put store \"departments\" \"d1\" {:name \"Engineering\"})

  (bridge/query store [\"users\" \"departments\"]
                '{:find [?uname ?dname]
                  :where [[?u :kotobase/coll \"users\"]
                          [?u :name ?uname]
                          [?u :dept-key ?dk]
                          [?d :kotobase/coll \"departments\"]
                          [?d :kotobase/key ?dk]
                          [?d :name ?dname]]}
                (constantly true))
  ;=> #{[\"Alice\" \"Engineering\"]}
  ```

  ## `visible?` is required, not defaulted

  Every query fn here takes `visible?` as a REQUIRED argument, the same
  discipline `arrangement.query`/`arrangement.datalog`/the retired `kqe`
  already enforce (ADR-2607050500, \"Query as first-class effect\" -- no
  permissive default to silently fall back on). Pass `(constantly true)` to
  see everything materialized -- that is a caller's explicit choice, never
  this bridge's default. `q`/`query` simply delegate arity to
  `arrangement.datalog/q`, which itself throws (arity error) if `visible?`
  is omitted -- there is no separate opt-out path to keep in sync.

  ## A note on `kqe`

  ADR-2607172300's dependency table names `kotoba-lang/kqe` alongside
  `kotoba-lang/arrangement`. As of this repo's landing, `kqe` is RETIRED --
  its content (`[s p o]` pattern routing) was merged into `arrangement` as
  `arrangement.query`, and the actual Datomic-shaped `:find`/`:where`
  Datalog engine (`arrangement.datalog/q`, used here) lives in `arrangement`
  too and never existed in `kqe` at all. This bridge therefore depends on
  `kotoba-lang/arrangement` only -- see `kqe`'s README (\"this repo is
  retired\") and `arrangement`'s own README/ADR-2607050700 for the merge."
  (:require [arrangement.core :as arr]
            [arrangement.datalog :as datalog]
            [kotobase.store :as st]))

;; ---------------------------------------------------------------- materialize

(defn- doc->datoms
  "One document `doc` (as read from `[coll k]`) -> a seq of `{:s :p :o}`
  datoms for entity `entity`. See the ns docstring's \"Doc -> datoms
  mapping\" section for the full contract. `:kotobase/coll`/`:kotobase/key`
  are NOT emitted here -- `materialize` adds those two synthetic datoms
  itself, since only the caller knows `coll`/`k`."
  [entity doc]
  (if (map? doc)
    (into []
          (mapcat (fn [[attr v]]
                    (if (and (coll? v) (not (map? v)))
                      (map (fn [el] {:s entity :p attr :o el}) v)
                      [{:s entity :p attr :o v}])))
          doc)
    [{:s entity :p :kotobase/value :o doc}]))

(defn- entity-id
  "The materialized entity for `[coll k]`. See ns docstring."
  [coll k]
  (keyword (str coll) (str k)))

(defn materialize
  "Materialize every document in each of `coll-keys` (a seq of
  `kotobase.store` collection identifiers, e.g. `[\"users\" \"departments\"]`)
  as datoms in one combined `arrangement.core` db (the `{:spo :pso :pos
  :ocp}` 4-covering index) -- ready to hand to `q`/`arrangement.datalog/q`.
  Reads `store` via `kotobase.store/-list` + `-get` only (never mutates it).

  v0.1 linear scan: every call does a full `-list` + `-get` of every
  collection and rebuilds the db from scratch -- see ns docstring."
  [store coll-keys]
  (reduce
   (fn [db coll]
     (reduce
      (fn [db k]
        (let [entity (entity-id coll k)
              doc (st/-get store coll k)
              datoms (into [{:s entity :p :kotobase/coll :o (str coll)}
                            {:s entity :p :kotobase/key :o (str k)}]
                           (doc->datoms entity doc))]
          (reduce arr/assert-quad db datoms)))
      db
      (st/-list store coll)))
   (arr/empty-db)
   coll-keys))


;; ------------------------------------------------------------------- memo
;; ADR-2607310900. This is a MEMO, not a cache: the key is a content address,
;; so a changed graph is a different key rather than a dirty entry and there is
;; no invalidation path to get wrong.
;;
;; Two properties make it safe, and both are properties of `materialize` above
;; rather than of anything here:
;;
;;   1. `materialize` takes no `visible?`. The predicate is applied by `q`, over
;;      an already-built db. So ONE materialized db is correct for every caller
;;      regardless of what any of them may see, and sharing it across principals
;;      is safe by construction. If `materialize` ever grows a `visible?`
;;      argument this whole namespace becomes unsound -- there is a test that
;;      says so out loud.
;;   2. The db is a value. Nothing handed out of here can be mutated by a
;;      caller into something the next caller sees.
;;
;; What is deliberately NOT memoised: the ANSWER. A result memo would need
;; `visible?` in its key, `visible?` is a function, and keying on one is how a
;; principal ends up reading another principal's rows.

(defn- lru-put
  "`m` with `k`->`v`, evicting the oldest insertion past `cap`. Insertion order
  is tracked explicitly rather than by relying on map ordering, which neither
  host promises above a handful of entries."
  [{:keys [entries order] :as m} k v cap]
  (let [order (conj (vec (remove #(= k %) order)) k)
        entries (assoc entries k v)
        excess (- (count order) cap)]
    (if (pos? excess)
      ;; assoc onto `m`, never build a fresh map: the eviction branch used to
      ;; return {:entries :order} and silently dropped :capacity/:hits/:misses,
      ;; so the call after the first eviction read a nil capacity. Caught by
      ;; the bounded-memo test, which is why that test exercises three
      ;; insertions into a capacity of two rather than stopping at the edge.
      (assoc m
             :entries (apply dissoc entries (take excess order))
             :order (vec (drop excess order)))
      (assoc m :entries entries :order order))))

(def default-memo-capacity
  "Materialized dbs to keep. Small on purpose: each one holds every datom of
  every collection it covers, so this is the knob that decides how much memory
  a query surface uses, and a large default would hide that."
  8)

(defn memo
  "A memo store for `materialize-memo`. Callers own it, so a test can hold one
  and a Worker can hold one per isolate without either reaching the other."
  ([] (memo default-memo-capacity))
  ([capacity]
   (assert (pos? capacity) "a capacity of zero would memoise nothing and still allocate")
   (atom {:entries {} :order [] :capacity capacity :hits 0 :misses 0})))

(defn materialize-memo
  "`materialize`, memoised on `version` -- a CONTENT ADDRESS for the state of
  `store` (the chain-head CID, in `kotobase-protocols-worker`'s case).

  `version` is REQUIRED and must not be nil. A caller with no content address
  has nothing that makes a cached db provably current, and defaulting it would
  turn this into a cache that never invalidates -- which is not a cache, it is
  a bug that returns yesterday's data. Such a caller should call `materialize`
  directly and pay for it.

  `version` must change whenever ANY document in `coll-keys` changes. A
  revision counter is not automatically good enough: a counter is unique only
  along one line of writes, and a content-addressed chain can fork such that
  two forks reach the same counter with different contents (ADR-2607310900)."
  [memo-atom store coll-keys version]
  (assert (some? version)
          "materialize-memo requires a non-nil version (a content address); use materialize when you have none")
  (let [k [version (vec (sort (map str coll-keys)))]
        cached (get-in @memo-atom [:entries k])]
    (if cached
      (do (swap! memo-atom update :hits inc) cached)
      (let [db (materialize store coll-keys)]
        (swap! memo-atom (fn [m] (-> (lru-put m k db (:capacity m))
                                     (update :misses inc))))
        db))))

(defn memo-stats
  "`{:size :capacity :hits :misses}` -- so a deploy can report whether the memo
  is earning its memory instead of assuming it."
  [memo-atom]
  (let [m @memo-atom]
    {:size (count (:entries m)) :capacity (:capacity m)
     :hits (:hits m) :misses (:misses m)}))


(defn db-for
  "The materialized db for `coll-keys`, memoised when `ctx` carries BOTH a
  `:query-memo` and a `:query-version`, and freshly built when it does not.

  This is the seam a protocol handler calls instead of `query`, so that a
  deployment able to name its state gets the memo and one that cannot still
  works unchanged. Neither half alone is enough: a memo with no version has
  nothing to key on, and a version with no memo has nowhere to put the result.

  The caller supplying `:query-version` is asserting it changes whenever any
  document in `coll-keys` changes. See `materialize-memo`."
  [{:keys [query-memo query-version]} store coll-keys]
  (if (and query-memo query-version)
    (materialize-memo query-memo store coll-keys query-version)
    (materialize store coll-keys)))

;; --------------------------------------------------------------------- query

(defn q
  "Run a Datomic-shaped Datalog `query` (`{:find [...] :where [...] :in
  [...] :rules [...]}`) over an already-materialized `db` (the return of
  `materialize`, or any `arrangement.core` db). `visible?` is REQUIRED (see
  ns docstring). Optional 4th arg `inputs` supplies `:in` parameter values,
  positional, same order as `:in`.

  A thin delegation to `arrangement.datalog/q` -- this bridge does not
  reimplement query evaluation."
  ([db query visible?] (datalog/q db query visible?))
  ([db query visible? inputs] (datalog/q db query visible? inputs)))

(defn query
  "Convenience: `(q (materialize store coll-keys) query visible? inputs)` in
  one call -- materialize `coll-keys` from `store` and immediately run
  `query` over the result. `visible?` is REQUIRED (see ns docstring).

  Prefer calling `materialize` once and `q` directly when running several
  queries over the same snapshot of data (e.g. in a test, or a batch of
  queries in one request) -- `query` re-materializes on every call, which
  is wasteful (and, per the v0.1 linear-scan limitation, may observe a
  different snapshot of `store` between two `query` calls if it is mutated
  concurrently)."
  ([store coll-keys query-form visible?]
   (q (materialize store coll-keys) query-form visible?))
  ([store coll-keys query-form visible? inputs]
   (q (materialize store coll-keys) query-form visible? inputs)))
