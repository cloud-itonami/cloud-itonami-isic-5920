(ns musicops.advisor
  "MusicOpsAdvisor -- the *contained intelligence node* for the
  ISIC-5920 sound-recording/music-publishing operations-coordination
  actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: recording-session/track/catalog data logging, studio-
  session/mastering scheduling proposals, outbound release/
  distribution coordination, and rights-conflict/sample-clearance/
  royalty-dispute concern flagging. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record and NEVER a direct
  actuation -- every proposal's `:effect` is always `:propose`. Every
  output is censored downstream by `musicops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts a proposal that directly finalizes a
  rights-licensing grant or a royalty-payment determination -- those
  are permanently out of scope for this actor, not merely
  un-implemented. `musicops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode
  (a compromised or confused advisor drifting into scope it must
  never touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :catalog-id  str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
     :confidence  0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-production-record
  "Draft a recording-session/track/catalog data log entry. Pure
  logging of observed production operations -- never a rights or
  royalty determination."
  [_db {:keys [catalog-id patch]}]
  {:op          :log-production-record
   :catalog-id  catalog-id
   :summary     (str catalog-id " の制作記録を記録: " (pr-str (keys patch)))
   :rationale   "録音セッション・トラック・カタログ情報の観察記録のみ。権利許諾や印税支払いの決定は行わない。"
   :cites       [catalog-id]
   :effect      :propose
   :value       (merge {:catalog-id catalog-id} patch)
   :confidence  0.93})

(defn- propose-production-schedule
  "Draft a studio-session/mastering scheduling PROPOSAL only (never a
  binding schedule change). Final confirmation is always done by the
  studio/label operations manager."
  [_db {:keys [catalog-id patch]}]
  {:op          :schedule-production-operation
   :catalog-id  catalog-id
   :summary     (str catalog-id " の制作スケジュール調整提案: " (pr-str (keys patch)))
   :rationale   "スタジオセッション・マスタリングの日程調整提案のみ。最終確定はスタジオ/レーベル運営責任者が行う。"
   :cites       [catalog-id]
   :effect      :propose
   :value       (merge {:catalog-id catalog-id} patch)
   :confidence  0.88})

(defn- propose-release-coordination
  "Draft an outbound release/distribution coordination request
  (scheduling/logistics only -- never a rights-licensing grant or a
  royalty-payment determination)."
  [_db {:keys [catalog-id patch]}]
  {:op          :coordinate-release
   :catalog-id  catalog-id
   :summary     (str catalog-id " のリリース/配信調整: " (pr-str (keys patch)))
   :rationale   "リリース・配信スケジュールの調整のみ。権利許諾や印税支払いの決定は含まない。"
   :cites       [catalog-id]
   :effect      :propose
   :value       (merge {:catalog-id catalog-id} patch)
   :confidence  0.90})

(defn- propose-rights-concern
  "Surface a rights-conflict/sample-clearance/royalty-dispute concern
  for HUMAN triage. This op ALWAYS escalates in `musicops.governor` --
  never auto-committed at any phase -- regardless of how confident the
  advisor is that the concern is real."
  [_db {:keys [catalog-id patch]}]
  {:op          :flag-rights-concern
   :catalog-id  catalog-id
   :summary     (str catalog-id " の権利懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale   "権利関係で観察された事実（サンプルクリアランスの未確認、権利者間の主張対立、印税配分の不一致等）の報告。常に人間の確認・対応が必要。この提案自体は権利許諾や印税支払いを一切確定しない。"
   :cites       [catalog-id]
   :effect      :propose
   :value       (merge {:catalog-id catalog-id} patch)
   :confidence  (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-production-record (propose-production-record _db request)
                   :schedule-production-operation (propose-production-schedule _db request)
                   :coordinate-release (propose-release-coordination _db request)
                   :flag-rights-concern (propose-rights-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalize the rights license and authorize the royalty payment")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :catalog-id (:catalog-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
