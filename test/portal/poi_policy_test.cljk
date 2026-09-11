(ns portal.poi-policy-test
  "The map slice's governor contract as executable tests
  (com-junkawasaki/root ADR-2607276000). Same single invariant as
  `portal.policy-contract-test`, applied to the two POI ops:

    PortalCurator-LLM never publishes a pin or discloses a search the
    PortalGovernor would reject, and every decision (commit OR hold)
    leaves exactly one ledger fact.

  Every failure case below is proposed by the advisor at HIGH confidence
  on purpose — the three map-slice gates are HARD and must not consult
  confidence at all."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [portal.report :as report]
            [portal.store :as store]
            [portal.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def geo-editor {:actor-id "geo-1" :actor-role :geo-editor :phase 3})
(def geo-editor-p1 (assoc geo-editor :phase 1))
(def advertiser {:actor-id "adv-1" :actor-role :advertiser-client :tenant "tenant-basic"})
(def kanto {:min-lat 35.0 :max-lat 36.5 :min-lng 139.0 :max-lng 140.5})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(def ^:private clean-poi
  "A commercial POI whose claimed entity resolves to the seeded issued LEI
  record, with a matching ISIC code and a cited source."
  {:op :poi/publish :subject "poi-300" :name "デモ書店支店(架空)"
   :lat 35.6812 :lng 139.7671 :category :retail :isic-code "4761"
   :lei "DEMO0000000000000001" :source-id "src-gov1"
   :residential? false :subject-name nil
   :source {:class :public-domain :ref "usc-17-105:demo"}})

(defn- basis-of [db] (-> (store/ledger db) first :basis))

;; ───────────────────────── the clean path ─────────────────────────

(deftest verified-poi-commits
  (let [[db actor] (fresh)
        res (exec-op actor "p1" clean-poi geo-editor)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "デモ書店支店(架空)" (:name (store/poi db "poi-300"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

;; ───────────────────────── entity-verification-gate ────────────────

(deftest commercial-poi-without-entity-is-held
  (testing "a business pin naming no legal entity → HARD hold, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "p2" (assoc clean-poi :lei nil) geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/poi db "poi-300")) "SSoT unchanged")
      (is (some #{:entity-verification-gate} (basis-of db))))))

(deftest unresolvable-entity-is-held
  (testing "an LEI that is not in the registry at all → HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p3" (assoc clean-poi :lei "DEMO0000000000009999") geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/poi db "poi-300")))
      (is (some #{:entity-verification-gate} (basis-of db))))))

(deftest lapsed-entity-is-held
  (testing "a registered but LAPSED entity → HARD hold (stale registration)"
    (let [[db actor] (fresh)
          res (exec-op actor "p4"
                       (assoc clean-poi :lei "DEMO0000000000000002" :isic-code "4711")
                       geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:entity-verification-gate} (basis-of db))))))

(deftest isic-contradiction-is-held
  (testing "a real entity filed under an ISIC code that contradicts the registry → HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p5" (assoc clean-poi :isic-code "5610") geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:entity-verification-gate} (basis-of db))
          "a silent mis-filing would corrupt every downstream :company/lei join"))))

(deftest non-commercial-poi-needs-no-entity
  (testing "a park asserts no business exists, so it is not gated on an LEI"
    (let [[db actor] (fresh)
          res (exec-op actor "p6"
                       (assoc clean-poi :subject "poi-400" :name "デモ広場(架空)"
                              :category :park :isic-code nil :lei nil)
                       geo-editor)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (some? (store/poi db "poi-400"))))))

;; ───────────────────────── geo-bounds-gate ─────────────────────────

(deftest dropped-coordinate-is-held
  (testing "a geocoder dropout (no coordinate at all) → HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p7" (assoc clean-poi :drop-coord? true) geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/poi db "poi-300")))
      (is (some #{:geo-bounds-gate} (basis-of db))))))

(deftest out-of-service-area-is-held
  (testing "a valid coordinate the operator cannot service → HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p8"
                       (assoc clean-poi :lat 34.6937 :lng 135.5023)
                       (assoc geo-editor :service-area kanto))]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:geo-bounds-gate} (basis-of db)))))
  (testing "the same pin inside the declared area commits"
    (let [[db actor] (fresh)
          res (exec-op actor "p8b" clean-poi (assoc geo-editor :service-area kanto))]
      (is (= :commit (get-in res [:state :disposition])))
      (is (some? (store/poi db "poi-300"))))))

;; ───────────────────────── residential-privacy-gate ────────────────

(deftest named-residence-is-held-and-not-overridable
  (testing "a private residence tied to a named person → HARD hold that never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "p9"
                       (assoc clean-poi :subject "poi-500" :name "デモ住宅(架空)"
                              :category :residence :isic-code nil :lei nil
                              :residential? true :subject-name "架空 太郎")
                       geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res))
          "a HARD gate must not offer an approver the chance to override it")
      (is (nil? (store/poi db "poi-500")) "SSoT unchanged")
      (is (some #{:residential-privacy-gate} (basis-of db))))))

(deftest unnamed-residence-is-allowed
  (testing "an address with no named occupant is not personal data under this gate"
    (let [[db actor] (fresh)
          res (exec-op actor "p10"
                       (assoc clean-poi :subject "poi-600" :name "デモ集合住宅入口(架空)"
                              :category :residence :isic-code nil :lei nil
                              :residential? true :subject-name nil)
                       geo-editor)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (some? (store/poi db "poi-600"))))))

;; ───────────────────────── shared gates, POI path ──────────────────

(deftest unsourced-poi-is-held
  (testing "a pin with no cited source → HARD hold, same as an uncited article"
    (let [[db actor] (fresh)
          res (exec-op actor "p11" (assoc clean-poi :unsourced? true) geo-editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (basis-of db))))))

(deftest wrong-role-cannot-publish-poi
  (testing ":content-editor may publish articles but not pins"
    (let [[db actor] (fresh)
          res (exec-op actor "p12" clean-poi
                       {:actor-id "ed-1" :actor-role :content-editor :phase 3})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= [:rbac] (basis-of db))))))

(deftest poi-publish-is-phase-gated-one-step-later
  (testing "phase 1 publishes articles but not pins"
    (let [[db actor] (fresh)
          res (exec-op actor "p13" clean-poi geo-editor-p1)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= :phase-disabled (-> (store/ledger db) first :phase-reason))))))

;; ───────────────────────── :poi/search disclosure ──────────────────

(deftest poi-search-within-tier-commits
  (let [[db actor] (fresh)
        res (exec-op actor "s1"
                     {:op :poi/search :subject "search-1"
                      :center-lat 35.6812 :center-lng 139.7671 :radius-km 5.0}
                     advertiser)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/ledger db))) "a governed read is still one auditable event")
    (is (nil? (store/poi db "search-1")) "a disclosure never writes the SSoT")))

(deftest poi-search-over-disclosure-is-held
  (testing "a basic-tier tenant asking for the fleet join keys and audit columns → HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "s2"
                       {:op :poi/search :subject "search-2" :greedy? true
                        :center-lat 35.6812 :center-lng 139.7671 :radius-km 5.0}
                       advertiser)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (basis-of db))))))

(deftest poi-search-with-invalid-center-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "s3"
                     {:op :poi/search :subject "search-3"
                      :center-lat nil :center-lng nil :radius-km 5.0}
                     advertiser)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:geo-bounds-gate} (basis-of db)))))

(deftest poi-search-unregistered-tenant-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "s4"
                     {:op :poi/search :subject "search-4"
                      :center-lat 35.6812 :center-lng 139.7671 :radius-km 5.0}
                     (assoc advertiser :tenant "tenant-ghost"))]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (basis-of db)))))

(deftest rendered-search-cannot-exceed-the-approved-columns
  (testing "the renderer emits exactly the governor-approved column set"
    (let [db (store/seed-db)
          cols [:poi-id :name :lat :lng :category :status :as-of :distance-km]
          rows (report/render-poi-search db {:lat 35.6812 :lng 139.7671} 5.0 cols)]
      (is (seq rows))
      (is (every? #(= (set cols) (set (keys %))) rows))
      (is (not-any? #(contains? % :lei) rows)
          "a basic-tier search must not leak the entity join key")
      (is (= ["poi-100" "poi-200"] (mapv :poi-id rows)) "nearest first"))))

(deftest held-poi-is-never-searchable
  (testing "a pin the write path rejected cannot leak through the read path"
    (let [[db actor] (fresh)]
      (exec-op actor "s5" (assoc clean-poi :lei nil) geo-editor)
      (is (nil? (store/poi db "poi-300")))
      (is (not-any? #(= "poi-300" (:poi-id %))
                    (report/render-poi-search db {:lat 35.6812 :lng 139.7671} 5.0
                                              [:poi-id :name :lat :lng]))))))
