(ns musicops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean production-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a production-schedule request,
  release coordination (both auto-commit clean at phase 3), then a
  rights-concern flag (ALWAYS escalates, at any phase -- approve, then
  commit), then HARD-hold scenarios: an unregistered catalog entry, a
  catalog entry registered but not yet verified, a proposal whose own
  `:effect` is not `:propose`, and a proposal that has drifted into
  the permanently-excluded rights-license-finalization/royalty-
  payment-determination scope."
  (:require [langgraph.graph :as g]
            [musicops.advisor :as advisor]
            [musicops.store :as store]
            [musicops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "label-ops-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :label-ops-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :label-ops-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-production-record catalog-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-production-record :catalog-id "catalog-1"
                                  :patch {:session-date "2026-07-10" :take-count 12}} coordinator-phase-1)]
      (println r)
      (println "-- human label-ops coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-production-record catalog-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-production-record :catalog-id "catalog-1"
                                  :patch {:session-date "2026-07-11" :take-count 4}} coordinator-phase-3))

    (println "\n== schedule-production-operation catalog-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-production-operation :catalog-id "catalog-1"
                                  :patch {:proposed-date "2026-07-18" :studio "Riverside A"}} coordinator-phase-3))

    (println "\n== coordinate-release catalog-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-release :catalog-id "catalog-1"
                                  :patch {:release-window "2026-08-01" :dsp-targets ["streaming" "digital-download"]}} coordinator-phase-3))

    (println "\n== flag-rights-concern catalog-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-rights-concern :catalog-id "catalog-1"
                                 :patch {:concern "possible uncleared sample in bridge section" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human label-ops coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-production-record catalog-99 (unregistered catalog entry -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-production-record :catalog-id "catalog-99"
                                  :patch {:session-date "2026-07-01"}} coordinator-phase-3))

    (println "\n== log-production-record catalog-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-production-record :catalog-id "catalog-3"
                                  :patch {:session-date "2026-07-01"}} coordinator-phase-3))

    (println "\n== schedule-production-operation catalog-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-production-operation :catalog-id "catalog-1"
                                           :patch {:proposed-date "2026-07-19"}} coordinator-phase-3)))

    (println "\n== log-production-record catalog-1, advisor drifts into rights-license/royalty-payment scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-production-record :catalog-id "catalog-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
