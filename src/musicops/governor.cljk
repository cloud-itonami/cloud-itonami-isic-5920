(ns musicops.governor
  "MusicOpsGovernor -- the independent compliance layer that earns the
  MusicOpsAdvisor the right to commit. The advisor has no notion of
  whether a catalog entry is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into
  a permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (recording-session/track/catalog data logging, studio-session/
  mastering scheduling proposals, outbound release/distribution
  coordination, rights-conflict/sample-clearance/royalty-dispute
  concern flagging). It NEVER performs or authorizes:
    - finalizing a rights-licensing grant (a decision to grant, deny,
      or execute a licensing agreement for a recording or composition)
    - finalizing a royalty-payment determination (a decision to
      authorize, execute, or issue a specific royalty payment, or to
      set a final royalty split/amount)

  Two HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Catalog unverified          -- the target catalog entry (the
                                       recording/work + its artist-
                                       contract record) must exist AND
                                       be independently confirmed
                                       `:registered?`/`:verified?` in
                                       the store before ANY proposal
                                       for it may commit or even
                                       escalate. Never trusts a
                                       proposal's own claim about the
                                       catalog entry -- re-derived from
                                       the catalog entry's own store
                                       record, the same 'ground truth,
                                       not self-report' discipline
                                       every sibling actor's governor
                                       uses.
    2. Effect not :propose         -- every proposal's `:effect` MUST
                                       be `:propose`. Any other effect
                                       value is, by construction, a
                                       claim to directly actuate/commit
                                       outside governance -- HARD
                                       block, not merely
                                       low-confidence.
    3. Scope exclusion             -- ANY proposal (regardless of op)
                                       whose op, rationale, summary,
                                       citations or draft value directly
                                       finalizes a rights-licensing
                                       grant or a royalty-payment
                                       determination is a HARD,
                                       PERMANENT block -- this actor's
                                       charter excludes that territory
                                       structurally, not as a rollout
                                       milestone. Evaluated
                                       UNCONDITIONALLY on every
                                       proposal. An op outside the
                                       closed four-op allowlist is the
                                       SAME failure mode (an advisor
                                       proposing something it was never
                                       authorized to propose) and is
                                       folded into this same check.

  CRITICAL, and un-overridable by rollout phase: `scope-excluded-terms`
  below is phrased as the finalization/execution ACTION (\"finalize the
  rights license\", \"authorize the royalty payment\"), never as a bare
  noun (\"license\", \"royalty\"). A bare-noun phrasing would self-trip
  on this actor's own core valid use cases -- every proposal generator
  in `musicops.advisor` legitimately talks ABOUT rights and royalties
  in its own disclaimer rationale (\"this proposal never finalizes a
  rights license or royalty payment\"), and `:flag-rights-concern`'s
  entire purpose is to report raw observations that legitimately
  mention sample clearance, rights disputes, and royalty splits (see
  `legitimate-rights-concern-is-not-scope-excluded` in
  `governor_test.clj` and `default-mock-advisor-proposals-never-self-
  trip-scope-exclusion` in `governor_contract_test.clj`, which assert
  the default mock advisor's own proposals -- including its own
  never-finalizes disclaimer text -- never self-trip this gate).

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-rights-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `musicops.phase` independently agrees: `:flag-rights-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one."
  (:require [kotoba.lang.text :as str]
            [musicops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-production-record :schedule-production-operation
    :coordinate-release :flag-rights-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-rights-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  rights-licensing grant or a royalty-payment determination.
  Deliberately phrased as the finalization/execution ACTION, never a
  bare noun -- see the governor docstring's CRITICAL note above for
  why a bare-noun phrasing would self-trip on this actor's own
  legitimate advisor disclaimers and `:flag-rights-concern` use case.
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent."
  ["finalize the rights license" "grant the rights license"
   "execute the licensing agreement" "authorize the license grant"
   "finalize the licensing agreement" "issue the rights license"
   "confirm the rights grant" "execute the rights transfer"
   "finalize the rights transfer" "approve the licensing agreement"
   "権利許諾を確定する" "ライセンス契約を締結する決定" "権利許諾を付与する決定"
   "権利移転を確定する" "使用許諾契約を承認する決定"
   "finalize the royalty payment" "authorize the royalty payment"
   "execute the royalty payment determination" "issue the royalty payment"
   "determine the final royalty amount" "finalize the royalty determination"
   "approve the royalty payout" "execute the royalty payout"
   "印税支払いを確定する" "印税支払いを承認する決定" "印税配分を最終決定する"
   "ロイヤルティの最終決定を下す" "印税支払いを実行する決定"])

;; ----------------------------- checks -----------------------------

(defn- catalog-unverified-violations
  "The target catalog entry must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:catalog-id` claim without a store lookup."
  [{:keys [catalog-id]} st]
  (let [r (store/catalog-entry st catalog-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :catalog-unverified
        :detail (str catalog-id " は未登録または未検証のカタログ項目 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content directly finalizes a rights-licensing grant or
  a royalty-payment determination, regardless of confidence or how
  clean every other check is. Evaluated UNCONDITIONALLY on every
  proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "権利許諾グラントの直接確定や印税支払い決定の直接確定に触れる提案は永久に禁止"}])))

(defn check
  "Censors a MusicOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [catalog-id (or (:catalog-id proposal) (:catalog-id request))
        hard (into []
                   (concat (catalog-unverified-violations {:catalog-id catalog-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :catalog-id (:catalog-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
