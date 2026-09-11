(ns musicops.store
  "SSoT for the ISIC-5920 sound-recording/music-publishing OPERATIONS
  COORDINATION actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every `cloud-itonami-isic-*`
  actor in this fleet uses.

  This actor coordinates the back-office operations of a recording
  studio and/or music publisher: recording-session/track/catalog data
  logging, studio-session/mastering scheduling proposals, outbound
  release/distribution coordination, and rights-conflict/sample-
  clearance/royalty-dispute concern flagging. It NEVER directly
  finalizes a rights-licensing grant or a royalty-payment
  determination -- see `musicops.governor`'s `scope-exclusion-
  violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `catalog` directory keyed by `:catalog-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt).

  A registered/verified catalog entry (the recording/work + its
  artist-contract record) must exist before ANY proposal targeting it
  may ever commit or escalate -- `musicops.governor`'s
  `catalog-unverified-violations` re-derives this from the catalog
  entry's own `:registered?`/`:verified?` fields, never from proposal
  self-report, the SAME 'ground truth, not self-report' discipline
  every sibling actor's own governor uses.

  The ledger stays append-only: which catalog entry a proposal
  targeted, which operation, on what basis, committed/held/escalated
  and approved by whom is always a query over an immutable log.")

(defprotocol Store
  (catalog-entry [s catalog-id] "Registered catalog record, or nil.
    Catalog map: {:catalog-id .. :artist .. :work-title .. :label ..
    :rights-holder .. :registered? bool :verified? bool}.")
  (all-catalog-entries [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-catalog-entries [s entries] "replace/seed the catalog directory (map catalog-id->catalog-entry)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained catalog directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:catalog
   {"catalog-1" {:catalog-id "catalog-1" :artist "River Static" :work-title "Low Tide (Master)"
                 :label "Riverside Recordings" :rights-holder "River Static Music Publishing"
                 :registered? true :verified? true}
    "catalog-2" {:catalog-id "catalog-2" :artist "Coral Line" :work-title "Salt & Signal EP"
                 :label "Riverside Recordings" :rights-holder "Coral Line Publishing Co."
                 :registered? true :verified? true}
    "catalog-3" {:catalog-id "catalog-3" :artist "Nine Fathom" :work-title "Undertow (unmixed rough)"
                 :label "Riverside Recordings" :rights-holder "Nine Fathom Music Publishing"
                 :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (catalog-entry [_ catalog-id] (get-in @a [:catalog catalog-id]))
  (all-catalog-entries [_] (sort-by :catalog-id (vals (:catalog @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-catalog-entries [s entries] (when (seq entries) (swap! a assoc :catalog entries)) s))

(defn seed-db
  "A MemStore seeded with the demo catalog directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `catalog` map (catalog-id string
  -> catalog-entry map) -- the primary test/dev entry point. `catalog`
  may be empty (an unregistered-everywhere store)."
  [catalog]
  (->MemStore (atom {:catalog (or catalog {}) :ledger [] :coordination-log []})))
