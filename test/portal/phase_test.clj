(ns portal.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is
  unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [portal.store :as store]
            [portal.operation :as op]))

(def editor {:actor-id "ed-1" :actor-role :content-editor})
(def adops  {:actor-id "ao-1" :actor-role :ad-ops})
(def tsafe  {:actor-id "ts-1" :actor-role :trust-safety-officer})

(def clean-publish
  {:op :listing/publish :subject "li-300" :title "デモ公共ドメイン記事"
   :snippet "米国連邦機関の公表資料からの転載(デモ)。" :source-id "src-gov1"
   :category :public-affairs :allegation-subject? false
   :source {:class :public-domain :ref "usc-17-105:demo"}})

(def clean-feature
  {:op :placement/feature :subject "slot-home-9" :listing-id "li-100"
   :sponsored? false :disclosure-label nil})

(def clean-report
  {:op :report/query :subject "li-100"})

(def takedown-req
  {:op :takedown/request :subject "li-100" :disputed-field :title :claim "訂正案"})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest missing-phase-context-does-not-grant-max-autonomy
  (testing "an ordinary caller who omits :phase gets the conservative default (1), not phase 3"
    (let [s (store/seed-db)
          actor (op/build s)
          res (g/run* actor {:request clean-publish :context editor} {:thread-id "no-phase"})]
      (is (= :interrupted (:status res)) "phase 1 forces approval, no silent auto-commit")
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-publish editor)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/listing s "li-300")) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "report/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-report {:actor-id "adv-1" :actor-role :advertiser-client :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-publish
  (testing "a clean publish that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-publish editor)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-feature-under-approval
  (let [[_ res] (run 2 clean-feature adops)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-publish
  (let [[s res] (run 3 clean-publish editor)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "デモ公共ドメイン記事" (:title (store/listing s "li-300"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (no source) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :listing/publish :subject "li-999" :title "X" :snippet "Y"
                          :source-id "src-cc1" :category :lifestyle :allegation-subject? false
                          :source nil}
                       editor)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest takedown-request-never-auto-commits-at-any-phase
  (testing "a takedown request never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph takedown-req tsafe)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a takedown"))))))
