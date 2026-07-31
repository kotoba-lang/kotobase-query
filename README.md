# kotobase-query

[![CI](https://github.com/kotoba-lang/kotobase-query/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kotobase-query/actions/workflows/ci.yml)

**The bridge from [`kotobase`](https://github.com/kotoba-lang/kotobase)'s flat document
store to real cross-record Datalog queries**, via
[`arrangement`](https://github.com/kotoba-lang/arrangement) — built as a shared
prerequisite (ADR-2607172300 in `com-junkawasaki/root`) for four sibling repos
(`datomic-client-shim`, `org-postgresql-wire`, `org-opencypher-cypher`,
`org-w3-sparql-protocol`) that each need real queries over kotobase-backed
data and should not each reimplement this bridge.

## The problem this solves

`kotobase.store`/`kotobase.local` (the `IStore` seam every kotobase-backed
app already uses) is a flat get/put/list(keys-in-one-collection)/append
store — no joins, no predicates across collections, no query language.
`kotoba-lang/arrangement` is a 4-covering triple index (`spo`/`pso`/`pos`/
`ocp` ≡ Datomic's EAVT/AEVT/AVET/VAET) with a real Datomic-shaped Datalog
engine (`arrangement.datalog/q`, `:find`/`:where`, joins, negation,
aggregates, recursive rules) over it — but nothing wired `IStore` documents
INTO that index. This repo is that wiring: a thin bridge, not a
reimplementation. `arrangement.datalog` does all the actual query
evaluation; this repo only materializes `IStore` docs as datoms and hands
them to it.

## Memoising it — `materialize-memo` (ADR-2607310900)

The linear scan below is still what `materialize` does. What changed is that a
caller holding a **content address** for the store's state no longer has to pay
for it twice:

```clojure
(def m (bridge/memo))                                   ; caller owns it
(bridge/materialize-memo m store ["users"] chain-cid)   ; version is REQUIRED
(bridge/memo-stats m)                                   ; {:size :capacity :hits :misses}
```

This is a **memo, not a cache**: the key is a content address, so a changed
graph is a *different key* rather than a dirty entry, and there is no
invalidation path to get wrong.

Two properties make sharing one db across callers safe, and both belong to
`materialize` rather than to the memo:

1. **`materialize` takes no `visible?`.** The predicate is applied by `q`, over
   an already-built db — so one db is correct for every caller regardless of
   what any of them may see. There is a test asserting `materialize`'s arity
   never grows, because if it did, this stops being sound.
2. **The db is a value**, so nothing handed out can be mutated into something
   the next caller sees.

**The answer is never memoised** — only the index. A result memo would need
`visible?` in its key, and keying on a function is how one principal ends up
reading another's rows.

`version` is required and must not be nil: a caller with no content address has
nothing that makes a cached db provably current, and defaulting it would
produce a cache that never invalidates. A revision *counter* is not
automatically sufficient either — a counter is unique only along one line of
writes, and a chain can fork.

## ⚠️ v0.1 limitation: linear materialization (read this before depending on it)

`materialize` does a **full `-list` + `-get` scan of every requested
collection, on every call**, building a throwaway in-memory `arrangement`
db that is discarded after the query runs. There is:

- **no incremental indexing** — every `materialize` call redoes the full scan,
  even if nothing changed since the last call;
- **no caching** — nothing is kept warm between calls;
- **no persistence** — `arrangement.core/commit!` (the CID-addressed
  snapshot machinery) is never called by this bridge; materialized data
  lives only in memory for the duration of one `materialize`/`q` call.

This is an accepted, explicitly-documented v0.1 scope decision
(ADR-2607172300), not an oversight: fine for the small/test-scale query
volume this bridge is built for right now. Real incremental indexing via
`arrangement`'s own index-maintenance primitives is a natural follow-up
once there is real query-volume evidence — not a v0.1 requirement.

## Doc → datoms mapping

For a document `doc` at `(kotobase.store/-get store coll k)`:

- the **entity** (`:s`) is `(keyword (str coll) (str k))` — e.g. collection
  `"users"` key `"u1"` becomes the entity `:users/u1`. Globally unique
  across every materialized collection.
- every entity always gets two synthetic attributes: **`:kotobase/coll`**
  (`(str coll)`) and **`:kotobase/key`** (`(str k)`) — these carry the
  original collection/key identity through materialization and are the
  join handle a doc in another collection uses to reference this one (see
  the worked example below).
- if `doc` is a map: every top-level `[attr v]` pair becomes a datom
  `{:s entity :p attr :o v}`. `attr` is used **as-is** (whatever key shape
  the document used — typically a keyword). If `v` is itself a non-map
  collection (vector/set/list), it is treated as a **Datomic-style
  cardinality-many attribute**: one datom per element, not one datom whose
  `:o` is the whole collection.
- if `doc` is not a map (a bare scalar, or `nil`): one fallback datom
  `{:s entity :p :kotobase/value :o doc}`.
- **nested maps as attribute values are stored as an opaque `:o` value**,
  not recursively flattened into their own entity — out of scope for v0.1,
  a documented limitation, not silently mishandled.

## API

```clojure
(require '[kotobase.local :as local]
         '[kotobase.store :as st]
         '[kotobase.query.bridge :as bridge])

;; materialize: IStore + collection keys -> one combined arrangement db
(bridge/materialize store coll-keys)

;; q: run a Datomic-shaped :find/:where query over an already-materialized db
;; visible? is REQUIRED (see below) — arity 3 or arity 4 with :in inputs
(bridge/q db query visible?)
(bridge/q db query visible? inputs)

;; query: one-shot convenience — materialize + q in a single call
(bridge/query store coll-keys query visible?)
(bridge/query store coll-keys query visible? inputs)
```

`visible?` is **required, not defaulted**, on every query fn here — the
same discipline `arrangement.query`/`arrangement.datalog`/the retired `kqe`
already enforce (ADR-2607050500, "Query as first-class effect" in
`com-junkawasaki/root` — no permissive default to silently fall back on).
Pass `(constantly true)` to see everything materialized; that is a
caller's explicit choice, never this bridge's default.

### Worked example (equality filter + cross-collection join)

```clojure
(def store (local/local-store))
(st/-put store "users" "u1" {:name "Alice" :role "admin" :dept-key "d1"})
(st/-put store "users" "u2" {:name "Bob" :role "user" :dept-key "d2"})
(st/-put store "departments" "d1" {:name "Engineering"})
(st/-put store "departments" "d2" {:name "Sales"})

(bridge/query store ["users" "departments"]
              '{:find [?uname ?dname]
                :where [[?u :role "admin"]
                        [?u :name ?uname]
                        [?u :dept-key ?dk]
                        [?d :kotobase/key ?dk]
                        [?d :name ?dname]]}
              (constantly true))
;=> #{["Alice" "Engineering"]}
```

`?u`'s `:dept-key` value ("d1") joins against `?d`'s `:kotobase/key`
attribute — a real cross-collection join, evaluated by
`arrangement.datalog`'s nested-loop join, not reimplemented here.

## A note on `kqe`

ADR-2607172300's dependency table names `kotoba-lang/kqe` alongside
`kotoba-lang/arrangement`. As of this repo's landing, **`kqe` is retired
upstream** — its content (`[s p o]` pattern routing) was merged into
`arrangement` as `arrangement.query`, and the Datomic-shaped `:find`/`:where`
Datalog engine used here (`arrangement.datalog/q`) lives in `arrangement`
and never existed in `kqe` at all. This repo therefore depends on
`kotoba-lang/arrangement` only — see `kqe`'s README ("this repo is
retired") and `arrangement`'s own README / ADR-2607050700 for the merge.

## Dependencies

- [`kotoba-lang/kotobase`](https://github.com/kotoba-lang/kotobase) —
  `kotobase.store`/`kotobase.local` (`IStore`, `LocalStore`), the seam this
  bridge reads from.
- [`kotoba-lang/arrangement`](https://github.com/kotoba-lang/arrangement) —
  `arrangement.core` (the 4-covering index this bridge writes into) and
  `arrangement.datalog` (the query engine this bridge delegates to).
  `arrangement.core` requires `prolly-tree.core`/`ipld.core` at
  namespace-load time (its `commit!`/CID-snapshot machinery, unused by
  this bridge but pulled in transitively) — those in turn require
  `io-multiformats`/`org-ietf-cbor`. `deps.edn`'s two direct git deps
  (`kotobase`, `arrangement`) resolve that whole chain automatically for
  the JVM `:test` alias via `tools.deps`; the nbb primary test path has no
  dependency resolver, so `bin/run_tests.cljs`/CI clone every transitive
  dep by hand — see Develop/test below.
- npm `@noble/hashes` — transitive JS-runtime dep of `io-multiformats`
  (`multiformats.core` requires `@noble/hashes/sha2.js` under `:cljs`; the
  JVM `:test` alias uses `java.security.MessageDigest` instead and needs
  no npm package). `io-multiformats`'s own `package.json` also lists a
  `"hashes": "2.0.1"` dependency, but nothing in its source actually
  requires the npm `hashes` package and no matching registry version
  exists (`npm install` 404s on it) — that entry is vestigial/broken
  upstream and deliberately **not** mirrored in this repo's
  `package.json`.

## Develop / test

First-class runtime is **nbb/cljs** (repo-wide runtime priority):

```bash
git clone https://github.com/kotoba-lang/kotobase .deps/kotobase
git clone https://github.com/kotoba-lang/arrangement .deps/arrangement
git clone https://github.com/kotoba-lang/prolly-tree .deps/prolly-tree
git clone https://github.com/kotoba-lang/io-ipld .deps/io-ipld
git clone https://github.com/kotoba-lang/io-multiformats .deps/io-multiformats
git clone https://github.com/kotoba-lang/org-ietf-cbor .deps/org-ietf-cbor
npm install
nbb --classpath "src:test:.deps/kotobase/src:.deps/arrangement/src:.deps/prolly-tree/src:.deps/io-ipld/src:.deps/io-multiformats/src:.deps/org-ietf-cbor/src" bin/run_tests.cljs
```

Each `.deps/<name>` should be checked out at the SHA pinned in `deps.edn`
(`kotobase`, `arrangement`) or in `arrangement`'s own `deps.edn`
transitively (`prolly-tree`, `io-ipld`, `io-multiformats`,
`org-ietf-cbor`) — CI pins every one of them, see
`.github/workflows/ci.yml`.

The `:test` alias in `deps.edn` is the JVM **compat** suite only (`clojure
-M:test`, via `tools.deps` transitive git-dep resolution — no manual
`.deps/` cloning needed for this path) — not the primary execution path.

## License

Apache-2.0
