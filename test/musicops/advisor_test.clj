(ns musicops.advisor-test
  "Unit tests of `musicops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [musicops.advisor :as adv]
            [musicops.store :as store]))

(def db (store/seed-db))

(deftest propose-production-record-shape
  (testing "production-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-production-record
                           :catalog-id "catalog-1"
                           :patch {:session-date "2026-07-10" :take-count 12}})]
      (is (= :log-production-record (:op p)))
      (is (= "catalog-1" (:catalog-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :catalog-id)))))

(deftest propose-production-schedule-shape
  (testing "production-schedule proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-production-operation
                           :catalog-id "catalog-2"
                           :patch {:proposed-date "2026-07-20"}})]
      (is (= :schedule-production-operation (:op p)))
      (is (= "catalog-2" (:catalog-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-release-coordination-shape
  (testing "release-coordination proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-release
                           :catalog-id "catalog-1"
                           :patch {:release-window "2026-08-01"}})]
      (is (= :coordinate-release (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-rights-concern-shape
  (testing "rights-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-rights-concern
                           :catalog-id "catalog-1"
                           :patch {:concern "possible uncleared sample"}})]
      (is (= :flag-rights-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-release :flag-rights-concern]]
      (let [p (adv/infer db {:op op :catalog-id "catalog-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-release :flag-rights-concern]]
      (let [p (adv/infer db {:op op :catalog-id "catalog-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
