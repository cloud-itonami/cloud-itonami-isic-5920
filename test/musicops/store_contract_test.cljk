(ns musicops.store-contract-test
  "Contract tests for `musicops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [musicops.store :as store]))

(deftest mem-store-catalog-lookup
  (testing "MemStore can store and retrieve catalog entries by ID (string keys)"
    (let [entries {"c1" {:catalog-id "c1" :artist "Reel One" :registered? true :verified? true}}
          s (store/mem-store entries)]
      (is (some? (store/catalog-entry s "c1")))
      (is (nil? (store/catalog-entry s "c99"))))))

(deftest mem-store-all-catalog-entries
  (testing "MemStore returns all catalog entries in sorted order"
    (let [entries {"c2" {:catalog-id "c2" :artist "Two"}
                   "c1" {:catalog-id "c1" :artist "One"}
                   "c3" {:catalog-id "c3" :artist "Three"}}
          s (store/mem-store entries)
          all-e (store/all-catalog-entries s)]
      (is (= 3 (count all-e)))
      (is (= "c1" (:catalog-id (first all-e))))
      (is (= "c3" (:catalog-id (last all-e)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-production-record :catalog-id "c1" :value {:take-count 10}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-catalog-entries
  (testing "MemStore with-catalog-entries replaces the catalog directory"
    (let [s (store/mem-store {})
          new-entries {"c1" {:catalog-id "c1" :artist "One"}}]
      (is (= 0 (count (store/all-catalog-entries s))))
      (store/with-catalog-entries s new-entries)
      (is (= 1 (count (store/all-catalog-entries s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo catalog entries"
    (let [s (store/seed-db)]
      (is (> (count (store/all-catalog-entries s)) 0))
      (is (some? (store/catalog-entry s "catalog-1")))
      (is (some? (store/catalog-entry s "catalog-2")))
      (is (some? (store/catalog-entry s "catalog-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for catalog-id"
    (let [demo (store/demo-data)
          entries (:catalog demo)]
      (doseq [[k v] entries]
        (is (string? k) "keys must be strings")
        (is (string? (:catalog-id v)) "catalog-id must be string")
        (is (= k (:catalog-id v)) "key must match catalog-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
