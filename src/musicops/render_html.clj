(ns musicops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo (com-junkawasaki/root
  ADR-2607189300): before this namespace existed there was NO demo page
  and NO generator here at all.

  Every figure on the rendered page is produced by actually running this
  repo's own actor stack -- `musicops.operation` (a langgraph-clj
  StateGraph) -> `musicops.advisor` -> `musicops.governor` ->
  `musicops.phase` -> `musicops.store`. Nothing on the page is typed by
  hand:

    - the page's own identity (activity name, ISIC code, domain,
      governor id, licence, maturity) is read out of this repo's
      `blueprint.edn`, so the header cannot drift from the registered
      blueprint the way a hand-typed title does;
    - the catalog directory rows come from `musicops.store/demo-data`
      via `store/all-catalog-entries`;
    - each coordination-run row is read off the langgraph final state
      returned by `g/run*` (verdict, disposition, violations);
    - the op-gate matrix is DERIVED from `musicops.governor/allowed-ops`,
      `governor/always-escalate-ops`, `governor/confidence-floor` and
      `musicops.phase/phases` -- it is not a prose restatement, so it
      cannot drift away from the code the way a hand-written table does;
    - the audit ledger and coordination log are the store's own
      append-only vectors;
    - the approver-retention disclosure is PROBED at render time (see
      `approver-retention`), never asserted, so it stays true if the
      store's record shape changes.

  Build-time invariants (this namespace THROWS rather than emitting a
  console that silently claims a governor it never exercised):

    1. the run must produce at least one `:governor-hold` fact;
    2. at least one of those must carry real governor `:violations`
       (a HARD, un-overridable hold -- as opposed to a phase-gate hold,
       which also lands under `:t :governor-hold` but with an empty
       violation vector);
    3. the set of HARD rules actually fired must equal
       `expected-hard-rules` below -- i.e. every hard check
       `musicops.governor` implements is demonstrated, and a check that
       silently stops firing fails the build instead of quietly
       shrinking the page.

  Deterministic: no timestamps, no random ids, no wall-clock. Two runs
  against the same seed are byte-identical (verify by diffing two
  consecutive outputs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [musicops.advisor :as advisor]
            [musicops.governor :as governor]
            [musicops.operation :as op]
            [musicops.phase :as phase]
            [musicops.store :as store]))

(def ^:private coordinator-id "coord-1")
(def ^:private approver-id "label-ops-coordinator-1")

(def ^:private blueprint-path
  "This repo's registered ISIC blueprint, relative to the repo root
  (the same cwd the default output path assumes)."
  "blueprint.edn")

(defn read-blueprint
  "The blueprint entity for this repo. THROWS if it is missing or
  empty rather than falling back to a hand-typed title -- a header
  that silently stops tracking `blueprint.edn` is exactly the kind of
  hand-written claim this generator exists to eliminate."
  []
  (let [f (io/file blueprint-path)]
    (when-not (.exists f)
      (throw (ex-info (str "refusing to render: " blueprint-path
                           " not found -- run from the repo root")
                      {:path (.getAbsolutePath f)})))
    (let [bp (first (edn/read-string (slurp f)))]
      (when-not (:itonami.blueprint/isic-rev4 bp)
        (throw (ex-info (str "refusing to render: " blueprint-path
                             " has no :itonami.blueprint/isic-rev4 entity")
                        {:parsed bp})))
      bp)))

(def ^:private blueprint-fields
  "The blueprint keys the identity table shows, in a FIXED order --
  never `(keys bp)`, which would make the page depend on map ordering."
  [[:itonami.blueprint/id "Blueprint id"]
   [:itonami.blueprint/name "Registered activity"]
   [:itonami.blueprint/isic-rev4 "ISIC rev.4"]
   [:itonami.blueprint/domain "Domain"]
   [:itonami.blueprint/governor "Governor"]
   [:itonami.blueprint/license "Licence"]
   [:itonami.blueprint/status "Status"]
   [:itonami.blueprint/maturity "Maturity"]
   [:itonami.blueprint/social-impact "Social impact"]])

(def ^:private expected-hard-rules
  "Every HARD rule `musicops.governor` can raise. The scenario list
  below must demonstrate all of them; `-main` fails the build if the
  observed set differs (a rule that stops firing, or a new rule nobody
  added a scenario for, both break the build loudly)."
  #{:catalog-unverified :effect-not-propose :scope-excluded :op-not-allowed})

;; ----------------------------- adversarial advisors -----------------------------
;; Two of the governor's HARD checks exist to catch a *compromised or
;; confused advisor*, so they cannot be reached through a well-behaved
;; advisor and a normal request. These two stand-ins inject exactly the
;; failure mode each check is written against -- the same technique
;; `musicops.sim` uses for `:effect :commit`.

(defn- direct-actuation-advisor
  "An advisor that claims a direct actuation (`:effect :commit`)
  instead of a proposal."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(defn- off-allowlist-advisor
  "An advisor that drafts a well-formed, confident, `:propose`-effect
  proposal for an op that is NOT in `governor/allowed-ops` -- i.e. it
  proposes something it was never authorized to propose."
  [op-kw]
  (reify advisor/Advisor
    (-advise [_ _store {:keys [catalog-id patch]}]
      {:op         op-kw
       :catalog-id catalog-id
       :summary    (str catalog-id " の" (name op-kw) " 提案")
       :rationale  "許可された操作リストの外側にある提案。"
       :cites      [catalog-id]
       :effect     :propose
       :value      (merge {:catalog-id catalog-id} patch)
       :confidence 0.95})))

;; ----------------------------- scenarios -----------------------------

(def ^:private scenarios
  "One coordination request each. `:catalog-id` values are exactly the
  ids seeded in `musicops.store/demo-data` (`catalog-1`..`catalog-3`),
  plus `catalog-99`, which is deliberately absent from the directory in
  order to exercise the unregistered branch of
  `catalog-unverified-violations`. No other identifier appears."
  [{:id "s01" :phase 1
    :note "Phase 1 (assisted-logging): governor-clean, but no op is auto-eligible at this phase, so the coordinator signs off."
    :request {:op :log-production-record :catalog-id "catalog-1"
              :patch {:session-date "2026-07-10" :take-count 12}}
    :approval {:status :approved :by approver-id}}

   {:id "s02" :phase 3
    :note "Phase 3 (supervised-auto): governor-clean and above the confidence floor, so it auto-commits."
    :request {:op :log-production-record :catalog-id "catalog-1"
              :patch {:session-date "2026-07-11" :take-count 4}}}

   {:id "s03" :phase 3
    :note "Studio/mastering scheduling proposal -- auto-eligible at phase 3."
    :request {:op :schedule-production-operation :catalog-id "catalog-2"
              :patch {:proposed-date "2026-07-18" :studio "Riverside A"}}}

   {:id "s04" :phase 3
    :note "Release/distribution coordination -- auto-eligible at phase 3."
    :request {:op :coordinate-release :catalog-id "catalog-2"
              :patch {:release-window "2026-08-01"
                      :dsp-targets ["streaming" "digital-download"]}}}

   {:id "s05" :phase 3
    :note "Rights concern: ALWAYS escalates at every phase (governor always-escalate-ops AND absent from every phase :auto set -- two independent layers). Coordinator approves."
    :request {:op :flag-rights-concern :catalog-id "catalog-1"
              :patch {:concern "possible uncleared sample in bridge section" :confidence 0.92}}
    :approval {:status :approved :by approver-id}}

   {:id "s06" :phase 3
    :note "Same escalation, coordinator REJECTS -- the human is a real gate in both directions, and the rejection is written to the ledger with no SSoT mutation."
    :request {:op :flag-rights-concern :catalog-id "catalog-2"
              :patch {:concern "royalty split disputed between co-writers" :confidence 0.91}}
    :approval {:status :rejected :by approver-id}}

   {:id "s07" :phase 1
    :note "Phase gate (NOT a governor violation): :coordinate-release is not yet enabled for writes at phase 1, so the rollout gate holds a proposal the governor itself cleared."
    :request {:op :coordinate-release :catalog-id "catalog-2"
              :patch {:release-window "2026-09-01" :dsp-targets ["streaming"]}}}

   {:id "s08" :phase 3
    :note "HARD hold -- the target catalog entry does not exist in the directory at all."
    :request {:op :log-production-record :catalog-id "catalog-99"
              :patch {:session-date "2026-07-01"}}}

   {:id "s09" :phase 3
    :note "HARD hold -- catalog-3 IS registered, but its :verified? flag is false in the store. Re-derived from the store record, never from the proposal's own claim."
    :request {:op :log-production-record :catalog-id "catalog-3"
              :patch {:session-date "2026-07-01"}}}

   {:id "s10" :phase 3 :advisor :direct-actuation
    :note "HARD hold -- the advisor claims a direct actuation (:effect :commit) rather than a proposal."
    :request {:op :schedule-production-operation :catalog-id "catalog-1"
              :patch {:proposed-date "2026-07-19"}}}

   {:id "s11" :phase 3
    :note "HARD hold -- the advisor's own rationale drifts into finalizing a rights-licensing grant / royalty payment. Permanently out of charter; no approval can override it."
    :request {:op :log-production-record :catalog-id "catalog-1"
              :out-of-scope? true :patch {}}}

   {:id "s12" :phase 3 :advisor :off-allowlist
    :note "HARD hold -- a confident, well-formed :propose proposal for an op outside the closed four-op allowlist."
    :request {:op :log-production-record :catalog-id "catalog-2"
              :patch {:amount 250000 :payee "River Static"}}}])

(def ^:private off-allowlist-op
  "The op the `:off-allowlist` advisor proposes -- chosen to be exactly
  the kind of decision this actor's charter permanently excludes."
  :finalize-royalty-payment)

(defn- advisor-for [k]
  (case k
    :direct-actuation (direct-actuation-advisor)
    :off-allowlist    (off-allowlist-advisor off-allowlist-op)
    nil))

(defn- run-scenario!
  "Executes one scenario against the shared store and returns the
  scenario map with the langgraph FINAL STATE attached. Every rendered
  field is read back out of that state -- nothing is re-derived by hand."
  [db {:keys [id phase request advisor approval] :as sc}]
  (let [actor (op/build db (cond-> {} advisor (assoc :advisor (advisor-for advisor))))
        ctx   {:actor-id coordinator-id :actor-role :label-ops-coordinator :phase phase}
        first-run (g/run* actor {:request request :context ctx} {:thread-id id})
        escalated? (= :escalate (get-in first-run [:state :disposition]))
        final (if (and approval escalated?)
                (g/run* actor {:approval approval} {:thread-id id :resume? true})
                first-run)]
    (assoc sc :escalated? escalated? :state (:state final))))

(defn run-demo!
  "Runs every scenario against ONE freshly seeded store. Returns
  `{:db .. :runs [..]}`."
  []
  (let [db (store/seed-db)]
    {:db db :runs (mapv (partial run-scenario! db) scenarios)}))

;; ----------------------------- approver probe -----------------------------

(def ^:private approver-key :approved-by)

(def ^:private approver-probe-paths
  "The places a committed record could plausibly carry the human
  approver. Probed in order; whichever the store actually retained is
  what the console reads and what it reports."
  [[approver-key] [:payload approver-key] [:value approver-key]])

(defn- approver-paths
  "The subset of `approver-probe-paths` that this stored record
  actually carries a value at."
  [record]
  (vec (filter #(some? (get-in record %)) approver-probe-paths)))

(defn- path-label [p] (str/join "/" (map name p)))

(defn- run-approver
  "The human who approved THIS run, read off this run's own audit
  trail, or nil if the run was never escalated to a human."
  [run]
  (->> (get-in run [:state :audit])
       (filter #(= :approval-granted (:t %)))
       last
       :by))

(defn- audit-approvals
  "`:approval-granted` facts across every run's audit trail -- the
  approval is visible HERE regardless of what the store retains."
  [runs]
  (->> runs
       (mapcat #(get-in % [:state :audit]))
       (filter #(= :approval-granted (:t %)))
       vec))

(defn- pair-records-with-runs
  "Pair each committed record in `store/coordination-log` with the run
  that produced it.

  Do NOT join on [op catalog-id]: that key is NOT unique -- two runs
  may legitimately log the same op against the same catalog entry at
  different phases, and the earlier run's approval then leaks onto the
  later auto-committed write, crediting a human who never saw it.
  (Observed: the phase-1 approved log of `catalog-1` and the phase-3
  auto-commit of `catalog-1` collide on that key.)

  `musicops.operation`'s `:commit` node writes exactly the `:record`
  channel it was handed, so each stored record is `=` to its run's own
  final `:record`. Pair positionally and VERIFY that equality; throw
  rather than emit a page that attributes a write to the wrong run."
  [records runs]
  (let [committed (filterv #(= :commit (get-in % [:state :disposition])) runs)]
    (when-not (= (count records) (count committed))
      (throw (ex-info "refusing to render: committed-record count does not match the number of committing runs"
                      {:records (count records) :committing-runs (count committed)})))
    (mapv (fn [record run]
            (when-not (= record (get-in run [:state :record]))
              (throw (ex-info "refusing to render: a committed record does not match its run's final :record"
                              {:run (:id run) :record record
                               :run-record (get-in run [:state :record])})))
            [record run])
          records committed)))

(defn- approver-retention
  "PROBE, do not assume. Some stores in this fleet drop the approver on
  the way into the SSoT (the record is destructured and only `:value`
  is kept). Rather than hardcoding a claim about this repo -- which
  would become a lie the moment the store is changed -- inspect the
  records that were actually committed and report what is there.

  Returns {:granted n :records n :approved-records n
           :retained-paths [..] :dropped-paths [..] :retained? bool}."
  [db runs]
  (let [records  (vec (store/coordination-log db))
        granted  (audit-approvals runs)
        with     (filter (comp seq approver-paths) records)
        retained (into (sorted-set) (map path-label (mapcat approver-paths with)))
        dropped  (into (sorted-set)
                       (->> approver-probe-paths
                            (remove #(some (fn [r] (some? (get-in r %))) records))
                            (map path-label)))]
    {:granted          (count granted)
     :records          (count records)
     :approved-records (count with)
     :retained-paths   (vec retained)
     :dropped-paths    (vec dropped)
     :retained?        (boolean (seq retained))}))

(defn- record-approver
  "Approver for one committed record, resolved from the store if the
  store kept it, otherwise from the audit fact -- and always labelled
  with which of the two it came from, so a reader can never confuse
  'nobody approved' with 'the store dropped it'."
  [record audit-by]
  (let [paths (approver-paths record)]
    (cond
      (seq paths) {:by (get-in record (first paths)) :source :record
                   :path (path-label (first paths))}
      audit-by    {:by audit-by :source :audit-only}
      :else       nil)))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- kw-str [k] (if (keyword? k) (name k) (str k)))

(defn- yes-no [b cls-yes cls-no]
  (if b
    (str "<span class=\"" cls-yes "\">yes</span>")
    (str "<span class=\"" cls-no "\">no</span>")))

(defn- tr [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- section builders -----------------------------

(defn- blueprint-value
  "Render one blueprint value. Sequential values keep the blueprint's
  own order -- they are not sorted or re-grouped here."
  [v]
  (cond
    (sequential? v) (str/join " &middot; " (map #(code (kw-str %)) v))
    (keyword? v)    (code (kw-str v))
    :else           (esc v)))

(defn- blueprint-section [bp]
  (section
   "Blueprint identity"
   (str "Read at build time from " (code blueprint-path)
        " &mdash; the same registered entity the fleet indexes this repo by. "
        "The page title and heading above are derived from these fields, so they "
        "cannot drift from the blueprint.")
   (table ["Field" "Value"]
          (for [[k label] blueprint-fields
                :when (some? (get bp k))]
            (tr (str (esc label) " <span class=\"muted\">" (code (str k)) "</span>")
                (blueprint-value (get bp k)))))))

(defn- last-fact-for [ledger catalog-id]
  (last (filter #(= catalog-id (:catalog-id %)) ledger)))

(defn- outcome-cell [{:keys [t violations phase-reason]}]
  (cond
    (nil? t) "<span class=\"muted\">no activity</span>"
    (= :committed t) "<span class=\"ok\">committed</span>"
    (= :approval-rejected t) "<span class=\"warn\">held &middot; approver rejected</span>"
    (and (= :governor-hold t) (seq violations))
    (str "<span class=\"critical\">HARD hold &middot; "
         (esc (kw-str (:rule (first violations)))) "</span>")
    (= :governor-hold t)
    (str "<span class=\"warn\">phase hold &middot; " (esc (kw-str (or phase-reason :gated))) "</span>")
    :else "<span class=\"muted\">in progress</span>"))

(defn- catalog-section [db ledger]
  (section
   "Catalog directory (SSoT)"
   (str "Seeded from " (code "musicops.store/demo-data") " and read back through "
        (code "store/all-catalog-entries") ". A proposal targeting an entry that is not "
        "independently " (code ":registered?") " AND " (code ":verified?")
        " here can never commit and can never even escalate &mdash; the governor re-derives "
        "this from the entry's own store record, never from what the proposal claims.")
   (table ["Catalog id" "Artist" "Work" "Label" "Rights holder" "Registered" "Verified" "Latest outcome"]
          (for [{:keys [catalog-id artist work-title label rights-holder registered? verified?]}
                (store/all-catalog-entries db)]
            (tr (code catalog-id) (esc artist) (esc work-title) (esc label) (esc rights-holder)
                (yes-no registered? "ok" "critical")
                (yes-no verified? "ok" "critical")
                (outcome-cell (last-fact-for ledger catalog-id)))))))

(defn- disposition-cell [{:keys [disposition]} verdict]
  (case disposition
    :commit   "<span class=\"ok\">commit</span>"
    :hold     (if (seq (:violations verdict))
                "<span class=\"critical\">HARD hold</span>"
                "<span class=\"warn\">hold</span>")
    :escalate "<span class=\"warn\">escalated</span>"
    (str "<span class=\"muted\">" (esc (kw-str disposition)) "</span>")))

(defn- verdict-cell [{:keys [ok? hard? escalate? high-stakes? confidence violations]}]
  (str/join " &middot; "
            (remove nil?
                    [(cond hard? "<span class=\"critical\">HARD</span>"
                           ok?   "<span class=\"ok\">clean</span>"
                           escalate? "<span class=\"warn\">escalate</span>"
                           :else "<span class=\"muted\">-</span>")
                     (when high-stakes? "always-escalate op")
                     (str "conf " (esc confidence))
                     (when (seq violations)
                       (esc (str/join ", " (map (comp kw-str :rule) violations))))])))

(defn- approval-cell [{:keys [approval escalated? state]}]
  (let [granted (->> (:audit state) (filter #(= :approval-granted (:t %))) last)
        rejected (->> (:audit state) (filter #(= :approval-rejected (:t %))) last)]
    (cond
      granted  (str "<span class=\"ok\">approved</span> by " (code (:by granted)))
      rejected (str "<span class=\"warn\">rejected</span> by " (code (:by approval)))
      escalated? "<span class=\"warn\">awaiting a human</span>"
      :else    "<span class=\"muted\">not required</span>")))

(defn- runs-section [runs]
  (section
   "Coordination runs (this build)"
   (str "One row per " (code "musicops.operation") " graph run. Verdict, disposition and "
        "violations are read directly off the langgraph final state returned by "
        (code "langgraph.graph/run*") " &mdash; not restated from the scenario description.")
   (table ["Run" "Phase" "Op" "Catalog" "Governor verdict" "Disposition" "Human" "What this exercises"]
          (for [{:keys [id phase request state note] :as r} runs
                :let [verdict (:verdict state)]]
            (tr (code id)
                (esc phase)
                (code (kw-str (:op request)))
                (code (:catalog-id request))
                (verdict-cell verdict)
                (disposition-cell state verdict)
                (approval-cell r)
                (esc note))))))

(defn- gate-section []
  (section
   ;; NOTE a literal U+00D7, not "&times;" -- `section` escapes its
   ;; title, so an entity here would reach the reader as raw markup.
   "Op gate × rollout phase (derived from the code, not described)"
   (str "Each cell is computed at build time from " (code "musicops.governor/allowed-ops") ", "
        (code "governor/always-escalate-ops") " and " (code "musicops.phase/phases")
        ", so it cannot drift from the implementation. Confidence floor: "
        (code governor/confidence-floor) ". Default phase: " (code phase/default-phase) ". "
        "Note that " (code ":flag-rights-concern") " is absent from every phase's auto set "
        "AND is a member of the governor's always-escalate set &mdash; two independent layers "
        "agree that a rights concern always reaches a human.")
   (table ["Op" "Writable at phase" "Auto-commit at phase" "Always escalates"]
          (for [op-kw (sort-by name governor/allowed-ops)
                :let [ps (sort (keys phase/phases))
                      writable (filter #(contains? (:writes (phase/phases %)) op-kw) ps)
                      auto     (filter #(contains? (:auto (phase/phases %)) op-kw) ps)]]
            (tr (code (str op-kw))
                (if (seq writable) (esc (str/join ", " writable)) "<span class=\"muted\">never</span>")
                (if (seq auto)
                  (str "<span class=\"ok\">" (esc (str/join ", " auto)) "</span>")
                  "<span class=\"warn\">never &mdash; human approval at every phase</span>")
                (yes-no (contains? governor/always-escalate-ops op-kw) "warn" "muted"))))))

(defn- hard-holds [ledger]
  (filter #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn- hard-hold-section [ledger]
  (let [holds (hard-holds ledger)]
    (section
     "HARD governor holds that actually fired"
     (str "Un-overridable. No human approval path exists for these &mdash; the graph routes "
          "straight to " (code ":hold") ", the SSoT is not touched, and the rejection itself "
          "is written to the append-only ledger. " (esc (count holds))
          " fired in this build, covering " (esc (count (into #{} (mapcat #(map :rule (:violations %)) holds))))
          " distinct rules; the build fails if that set is not exactly "
          (code (str/join ", " (sort (map name expected-hard-rules)))) ".")
     (table ["Rule" "Op" "Catalog" "Advisor confidence" "Governor detail"]
            (for [f holds
                  v (:violations f)]
              (tr (str "<span class=\"critical\">" (esc (kw-str (:rule v))) "</span>")
                  (code (kw-str (:op f)))
                  (code (:catalog-id f))
                  (esc (:confidence f))
                  (esc (:detail v))))))))

(defn- scope-terms-section []
  (section
   "Permanently excluded decision areas"
   (str "This actor coordinates operations only. Finalizing a rights-licensing grant or a "
        "royalty-payment determination is outside its charter structurally &mdash; not a rollout "
        "milestone still to come. " (code "musicops.governor/scope-excluded-terms")
        " scans every advisor-authored field (op, summary, rationale, citations, draft value) for "
        (esc (count governor/scope-excluded-terms))
        " finalization phrases. Each is phrased as the finalizing ACTION, never as the bare noun "
        (code "license") " / " (code "royalty") ", because a bare noun would self-trip on this "
        "actor's own legitimate " (code ":flag-rights-concern") " reports and on the advisor's own "
        "never-finalizes disclaimer.")
   (str "    <p>"
        (str/join " &middot; " (map #(str "<code>" (esc %) "</code>") governor/scope-excluded-terms))
        "</p>\n")))

(defn- ledger-section [ledger]
  (section
   "Audit ledger (append-only)"
   (str "Every decision fact this build produced, in order, straight out of "
        (code "store/ledger") ". Holds are recorded as durably as commits &mdash; a rejected "
        "proposal leaves the same kind of trace as an accepted one.")
   (table ["#" "Fact" "Op" "Catalog" "Actor" "Disposition" "Basis"]
          (map-indexed
           (fn [i {:keys [t op catalog-id actor disposition basis phase-reason]}]
             (tr (esc (inc i))
                 (code (kw-str t))
                 (code (kw-str op))
                 (code catalog-id)
                 (esc (or actor ""))
                 (esc (kw-str (or disposition "")))
                 (esc (str/join ", " (remove nil? (concat (map kw-str basis)
                                                          (when phase-reason [(kw-str phase-reason)])))))))
           ledger))))

(defn- retention-note [{:keys [granted records approved-records retained-paths dropped-paths retained?]}]
  (str "Probed at build time, never assumed: " (esc granted)
       " approval(s) were granted in the audit trail and " (esc records)
       " record(s) reached the SSoT, of which " (esc approved-records)
       " carry an approver identity. "
       (if retained?
         (str "The approver survives into the stored record at "
              (str/join " and " (map #(str "<code>" (esc %) "</code>") retained-paths))
              (when (seq dropped-paths)
                (str ", but NOT at "
                     (str/join " / " (map #(str "<code>" (esc %) "</code>") dropped-paths))
                     " &mdash; a reader inspecting only those keys could not tell "
                     "&ldquo;nobody approved&rdquo; from &ldquo;the store dropped it&rdquo;"))
              ". The Approver column below therefore reads the path the store actually kept.")
         (str "<span class=\"critical\">The store does not retain the approver on any probed key</span>"
              " &mdash; the Approver column below is joined from the audit fact instead and is "
              "labelled <em>audit only &mdash; not retained in record</em>."))))

(defn- coordination-section [db runs retention]
  (let [pairs (pair-records-with-runs (vec (store/coordination-log db)) runs)]
    (section
     "Committed coordination log (SSoT writes)"
     (str "The only writes this build made. Each row is paired with the run that "
          "produced it by record identity, not by op/catalog &mdash; so an approval "
          "granted in one run can never be credited to a different run's "
          "auto-commit. " (retention-note retention))
     (table ["#" "Run" "Op" "Catalog" "Committed value" "Approver"]
            (map-indexed
             (fn [i [{:keys [op catalog-id value] :as record} run]]
               (let [{:keys [by source path]} (record-approver record (run-approver run))]
                 (tr (esc (inc i))
                     (code (:id run))
                     (code (kw-str op))
                     (code catalog-id)
                     (code (pr-str value))
                     (cond
                       (= :record source)
                       (str "<span class=\"ok\">" (esc by) "</span> <span class=\"muted\">("
                            (esc path) ")</span>")
                       (= :audit-only source)
                       (str "<span class=\"warn\">" (esc by)
                            "</span> <span class=\"muted\">(audit only &mdash; not retained in record)</span>")
                       :else "<span class=\"muted\">auto-committed &mdash; no approval required</span>"))))
             pairs)))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a store `db` that has already been
  driven by `run-demo!`, plus the run states it returned."
  [{:keys [db runs blueprint]}]
  (when-not blueprint
    (throw (ex-info "render called without a :blueprint -- see read-blueprint" {})))
  (let [ledger (vec (store/ledger db))
        retention (approver-retention db runs)
        activity (:itonami.blueprint/name blueprint)
        isic (:itonami.blueprint/isic-rev4 blueprint)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>" (esc (:itonami.blueprint/id blueprint))
     " &middot; " (esc activity) " operator console</title><style>\n"
     (jp-go-dds.skin/dds+skin)
     "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>" (esc activity) " (ISIC " (esc isic) ") &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; never finalizes a rights licence or a royalty payment</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p>Generated at build time by <code>musicops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by actually running this repo's actor: "
     "<code>musicops.operation</code> (a langgraph-clj StateGraph) &rarr; <code>musicops.advisor</code> "
     "&rarr; <code>musicops.governor</code> &rarr; <code>musicops.phase</code> &rarr; "
     "<code>musicops.store</code>. Every row below is read back out of that run or out of the "
     "seeded store. Nothing is hand-typed, and the build fails outright if the governor's HARD "
     "holds do not fire.</p>\n"
     "    <p class=\"muted\">" (esc (count runs)) " coordination runs &middot; "
     (esc (count ledger)) " audit facts &middot; "
     (esc (count (store/coordination-log db))) " SSoT writes &middot; "
     (esc (count (hard-holds ledger))) " HARD governor holds. Deterministic: no clock, no random "
     "ids &mdash; two consecutive builds are byte-identical.</p>\n"
     "  </section>\n"
     (blueprint-section blueprint)
     (catalog-section db ledger)
     (runs-section runs)
     (gate-section)
     (hard-hold-section ledger)
     (scope-terms-section)
     (ledger-section ledger)
     (coordination-section db runs retention)
     "</main>\n"
     "<footer><p class=\"muted\">cloud-itonami-isic-5920 &middot; AGPL-3.0-or-later &middot; "
     "regenerate with <code>clojure -M:dev:render-html</code></p></footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn- assert-governor-fired!
  "Build-time invariant, not a comment: a console that shows a governor
  which never actually held anything is theatre. Throws unless the run
  really produced HARD holds covering every rule the governor implements."
  [ledger]
  (let [gh    (filter #(= :governor-hold (:t %)) ledger)
        hard  (hard-holds ledger)
        rules (into #{} (mapcat #(map :rule (:violations %)) hard))]
    (when (empty? gh)
      (throw (ex-info "refusing to write the console: the run produced ZERO :governor-hold facts"
                      {:ledger-facts (count ledger)})))
    (when (empty? hard)
      (throw (ex-info "refusing to write the console: :governor-hold facts exist but none carries governor :violations (no HARD hold fired)"
                      {:governor-holds (count gh)})))
    (when (not= expected-hard-rules rules)
      (throw (ex-info "refusing to write the console: the HARD rules exercised do not match the governor's rule set"
                      {:expected expected-hard-rules
                       :observed rules
                       :missing (into #{} (remove rules expected-hard-rules))
                       :unexpected (into #{} (remove expected-hard-rules rules))})))
    {:governor-holds (count gh) :hard-holds (count hard) :rules rules}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        {:keys [governor-holds hard-holds rules]} (assert-governor-fired! ledger)
        html (render (assoc result :blueprint (read-blueprint)))]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, " (count runs) " runs, "
                  (count ledger) " ledger facts, "
                  (count (store/coordination-log db)) " SSoT writes, "
                  governor-holds " governor holds of which " hard-holds " HARD, rules "
                  (str/join "/" (sort (map name rules))) ")"))
    (println "approver retention (probed):" (pr-str (approver-retention db runs)))))
