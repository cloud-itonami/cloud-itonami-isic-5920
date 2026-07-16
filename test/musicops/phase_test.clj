(ns musicops.phase-test
  "Unit tests of `musicops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [musicops.phase :as phase]))

(def clean-verdict {:hard? false :escalate? false})
(def low-conf-verdict {:hard? false :escalate? true})
(def hard-verdict {:hard? true :escalate? false})

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-release :flag-rights-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-production-record-only
  (testing "phase 1 allows only production-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-production-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-production-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-production-record :schedule-production-operation :coordinate-release]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-rights-sensitive ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-production-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-production-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-release} :commit)]
      (is (= :commit disposition)))))

(deftest rights-concern-holds-when-not-enabled
  (testing ":flag-rights-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-rights-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-rights-concern yet"))))))

(deftest rights-concern-escalates-when-enabled
  (testing ":flag-rights-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-rights-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate rights concerns regardless of governor disposition"))))

(deftest rights-concern-never-in-any-auto-set
  (testing "structural invariant: :flag-rights-concern is never a member of any phase's :auto set"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-rights-concern))
          (str "phase " ph " :auto set must never contain :flag-rights-concern")))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-production-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
