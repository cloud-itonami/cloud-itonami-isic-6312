(ns portal.marketdata-bridge-test
  "Only runs under `clojure -M:market-data:dev:test` (needs
  cloud-itonami-isic-6311 checked out as a sibling repo). The single
  invariant under test: a market-data-sourced listing is never special-
  cased past portal's own governance — if isic-6311's OWN governed query
  does not commit, the resulting portal listing HARD-fails source-
  provenance too, exactly like an ordinary unsourced listing would."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketdata.store :as md-store]
            [marketdata.operation :as md-op]
            [portal.store :as store]
            [portal.operation :as op]
            [portal.marketdata-bridge :as bridge]))

(def editor-p3 {:actor-id "ed-1" :actor-role :content-editor :phase 3})
(def subscriber-basic {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic"})
(def subscriber-ghost {:actor-id "sub-2" :actor-role :subscriber :tenant "tenant-ghost"})

(deftest market-data-sourced-listing-commits-when-upstream-query-commits
  (testing "isic-6311 :disclosure/query commits (licensed tenant-basic) -> bridge cites a real source -> portal publishes"
    (let [md-db (md-store/seed-db)
          md-actor (md-op/build md-db)
          req (bridge/market-quote-listing-request md-actor md-db "fx-100" subscriber-basic "bridge-t1")]
      (is (= {:class :cloud-itonami-market-data-feed :ref "cloud-itonami-isic-6311:fx-100"}
             (:source req))
          "upstream query committed -> a real, non-nil source citation")
      (let [portal-db (store/seed-db)
            portal-actor (op/build portal-db)
            res (g/run* portal-actor {:request req :context editor-p3} {:thread-id "portal-t1"})]
        (is (= :commit (get-in res [:state :disposition])))
        (is (= (:id (store/listing portal-db "market-fx-100")) "market-fx-100"))))))

(deftest market-data-sourced-listing-holds-when-upstream-query-does-not-commit
  (testing "isic-6311 :disclosure/query held (unregistered tenant-ghost) -> bridge emits :source nil -> portal's OWN source-provenance-gate HARD-rejects, same as any other unsourced listing -- no special-casing"
    (let [md-db (md-store/seed-db)
          md-actor (md-op/build md-db)
          req (bridge/market-quote-listing-request md-actor md-db "fx-100" subscriber-ghost "bridge-t2")]
      (is (nil? (:source req))
          "upstream query did NOT commit -> bridge refuses to fabricate a source citation")
      (let [portal-db (store/seed-db)
            portal-actor (op/build portal-db)
            res (g/run* portal-actor {:request req :context editor-p3} {:thread-id "portal-t2"})]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:source-provenance-gate} (-> (store/ledger portal-db) first :basis)))
        (is (nil? (store/listing portal-db "market-fx-100")) "no listing written")))))
