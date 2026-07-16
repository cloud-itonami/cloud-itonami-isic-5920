(ns musicops.governor-test
  "Pure unit tests of `musicops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-
  test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [musicops.governor :as gov]
            [musicops.advisor :as advisor]
            [musicops.store :as store]))

(def catalog-1 {:catalog-id "catalog-1" :artist "River Static" :registered? true :verified? true})
(def catalog-3 {:catalog-id "catalog-3" :artist "Nine Fathom" :registered? true :verified? false})

(defn- clean-proposal [op catalog-id]
  {:op op :catalog-id catalog-id :summary "s" :rationale "routine operations coordination"
   :cites [catalog-id] :effect :propose :value {} :confidence 0.85})

(deftest catalog-entry-unregistered-is-hard
  (testing "no catalog record at all -> HARD hold"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "unknown-catalog") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:catalog-unverified} (map :rule (:violations verdict)))))))

(deftest catalog-entry-unverified-is-hard
  (testing "catalog registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"catalog-3" catalog-3})
          verdict (gov/check {} nil (clean-proposal :log-production-record "catalog-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:catalog-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-production-operation "catalog-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          verdict (gov/check {} nil (clean-proposal :override-rights-decision "catalog-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest rights-license-finalization-content-is-hard-and-permanent
  (testing "a proposal whose rationale directly finalizes a rights-licensing grant is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          poisoned (assoc (clean-proposal :log-production-record "catalog-1")
                          :rationale "decided to finalize the rights license and grant the rights license to the requesting distributor"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest royalty-payment-determination-content-is-hard
  (testing "a proposal directly finalizing a royalty-payment determination is HARD-blocked, same as rights-license finalization"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          poisoned (assoc (clean-proposal :schedule-production-operation "catalog-1")
                          :summary "will authorize the royalty payment for this quarter's streaming revenue"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest rights-transfer-execution-content-is-hard
  (testing "a proposal touching direct execution of a rights transfer is HARD-blocked"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          poisoned (assoc (clean-proposal :coordinate-release "catalog-1")
                          :value {:plan "execute the rights transfer to the new publisher before distribution"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest royalty-payout-execution-content-is-hard
  (testing "a proposal touching direct execution of a royalty payout is HARD-blocked"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          poisoned (assoc (clean-proposal :flag-rights-concern "catalog-1")
                          :rationale "recommend to execute the royalty payout and approve the royalty payout immediately"
                          :value {:concern "dispute flagged but paying out anyway"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-rights-concern-is-not-scope-excluded
  (testing "flagging observed sample-clearance/royalty-split/rights-dispute concerns as a RIGHTS CONCERN (not a finalized decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"catalog-1" catalog-1})
          concern (assoc (clean-proposal :flag-rights-concern "catalog-1")
                         :rationale "権利関係で観察された事実（サンプルクリアランスの未確認、権利者間の主張対立、印税配分の不一致等）の報告。常に人間の確認・対応が必要。この提案自体は権利許諾や印税支払いを一切確定しない。"
                         :value {:concern "possible uncleared sample in bridge section, and a royalty split disagreement between two credited writers"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (sample clearance, royalty splits, rights disputes) is exactly what this op exists to surface"))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "CRITICAL regression: every op the default mock advisor can produce, on a clean happy-path request, must NEVER trip scope-exclusion on its own default rationale/summary/disclaimer text -- a bare-noun-phrased scope-excluded-terms entry has previously self-blocked sibling actors' own happy paths in this fleet; scope-excluded-terms here are phrased as finalization/execution ACTIONS, not bare nouns, specifically to avoid this"
    (let [s (store/mem-store {"catalog-1" catalog-1})]
      (doseq [op [:log-production-record :schedule-production-operation
                  :coordinate-release :flag-rights-concern]]
        (let [proposal (advisor/infer nil {:op op :catalog-id "catalog-1"
                                            :patch {:take-count 5 :concern "royalty split disagreement noted, no clearance issue confirmed yet"}})
              verdict (gov/check {} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "op " op "'s own default mock-advisor proposal must never self-trip scope-exclusion"))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "op " op " must be in the closed allowlist")))))))
