(ns portal.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`'s policy_contract_test / robotaxi's
  safety_contract_test. The single invariant under test:

    PortalCurator-LLM never publishes/features/discloses/resolves a
    record the PortalGovernor would reject, and every decision (commit OR
    hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [portal.store :as store]
            [portal.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def editor {:actor-id "ed-1" :actor-role :content-editor})
(def adops  {:actor-id "ao-1" :actor-role :ad-ops})
(def tsafe  {:actor-id "ts-1" :actor-role :trust-safety-officer})
(def editor-p3 (assoc editor :phase 3))
(def adops-p3  (assoc adops :phase 3))
;; default-phase is 1 (assisted, no auto-commit for :placement/feature or
;; :takedown/request -- see phase.cljc's default-phase docstring). Tests
;; that specifically exercise governor-clean escalate/commit behavior opt
;; into phase 2+ explicitly (the same way phase_test.clj parameterizes
;; phase) -- they are not testing "what happens with no :phase set" (that
;; is missing-phase-context-does-not-grant-max-autonomy's job, in
;; phase_test.clj).
(def adops-p2  (assoc adops :phase 2))
(def tsafe-p2  (assoc tsafe :phase 2))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-publish-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :listing/publish :subject "li-300" :title "デモ公共ドメイン記事"
                   :snippet "米国連邦機関の公表資料からの転載(デモ)。" :source-id "src-gov1"
                   :category :public-affairs :allegation-subject? false
                   :source {:class :public-domain :ref "usc-17-105:demo"}}
                  editor-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "デモ公共ドメイン記事" (:title (store/listing db "li-300"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "an :advertiser-client role has no publish permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :listing/publish :subject "li-300" :title "X" :snippet "Y"
                     :source-id "src-gov1" :category :public-affairs :allegation-subject? false
                     :source {:class :public-domain :ref "demo"}}
                    {:actor-id "adv-1" :actor-role :advertiser-client})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/listing db "li-300")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest unsourced-listing-is-held
  (testing "a listing with no source citation (dropped feed header) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :listing/publish :subject "li-400" :title "X" :snippet "Y"
                     :source-id "src-cc1" :category :lifestyle :allegation-subject? false
                     :source {:class :cc-attribution :ref "demo"} :unsourced? true}
                    editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/listing db "li-400")) "no listing written"))))

(deftest expired-content-license-is-held
  (testing "a :licensed-syndication citation whose license-id is inactive → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3b"
                    {:op :listing/publish :subject "li-410" :title "X" :snippet "Y"
                     :source-id "src-synd1" :category :news :allegation-subject? false
                     :source {:class :licensed-syndication :ref "lic-expired:demo" :license-id "lic-expired"}}
                    editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest excerpt-over-cap-is-held
  (testing "a fair-use-excerpt listing whose snippet exceeds the conservative cap → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :listing/publish :subject "li-500" :title "X"
                     :snippet (apply str (repeat 500 "a")) :source-id "src-news1"
                     :category :news :allegation-subject? false
                     :source {:class :fair-use-excerpt :ref "demo"}}
                    editor)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:license-scope-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/listing db "li-500"))))))

(deftest sponsored-without-disclosure-is-held
  (testing "a sponsored placement with no disclosure label → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :placement/feature :subject "slot-x" :listing-id "li-100"
                     :sponsored? true :disclosure-label "Sponsored" :no-disclosure? true}
                    adops)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:disclosure-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/placement db "slot-x"))))))

(deftest sponsored-with-disclosure-commits-directly
  (testing "a clean, disclosed sponsored placement auto-serves at phase 3"
    (let [[db actor] (fresh)
          res (exec-op actor "t5b"
                    {:op :placement/feature :subject "slot-y" :listing-id "li-100"
                     :sponsored? true :disclosure-label "Sponsored"}
                    adops-p3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= "Sponsored" (:disclosure-label (store/placement db "slot-y")))))))

(deftest uncontracted-report-is-held
  (testing "a report query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :report/query :subject "li-100"}
                    {:actor-id "adv-2" :actor-role :advertiser-client :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-disclosure-beyond-tier-is-held
  (testing "a report query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :report/query :subject "li-100" :greedy? true}
                    {:actor-id "adv-1" :actor-role :advertiser-client :tenant "tenant-basic"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest sensitive-subject-feature-escalates-then-human-decides
  (testing "featuring a listing flagged as concerning an allegation subject interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t8"
                   {:op :placement/feature :subject "slot-z" :listing-id "li-200"
                    :sponsored? false :disclosure-label nil}
                   adops-p2)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :sensitive-subject (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "trustsafety-1"}}
                         {:thread-id "t8" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= "li-200" (:listing-id (store/placement db "slot-z"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t9"
                  {:op :placement/feature :subject "slot-z2" :listing-id "li-200"
                   :sponsored? false :disclosure-label nil}
                  adops-p2)
          r2 (g/run* actor {:approval {:status :rejected :by "trustsafety-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/placement db "slot-z2"))))))

(deftest takedown-request-always-escalates-regardless-of-confidence
  (testing "a takedown request always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          before (store/listing db "li-100")
          r1 (exec-op actor "t10"
                   {:op :takedown/request :subject "li-100" :disputed-field :title
                    :claim "訂正案"}
                   tsafe-p2)]
      (is (= :interrupted (:status r1)))
      (is (= :takedown-dispute (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the correction"
        (let [r2 (g/run* actor {:approval {:status :approved :by "trustsafety-1"}}
                         {:thread-id "t10" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= "訂正案" (:title (store/listing db "li-100"))))))
      (testing "a second, rejected takedown leaves the listing unchanged"
        (let [[db2 actor2] (fresh)
              _  (exec-op actor2 "t11"
                      {:op :takedown/request :subject "li-100" :disputed-field :title
                       :claim "訂正案"}
                      tsafe-p2)
              r3 (g/run* actor2 {:approval {:status :rejected :by "trustsafety-1"}}
                        {:thread-id "t11" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= (:title before) (:title (store/listing db2 "li-100")))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :listing/publish :subject "li-300" :title "X" :snippet "Y"
                          :source-id "src-gov1" :category :public-affairs
                          :allegation-subject? false
                          :source {:class :public-domain :ref "demo"}}
               editor-p3)
      (exec-op actor "b" {:op :listing/publish :subject "li-400" :title "X" :snippet "Y"
                          :source-id "src-cc1" :category :lifestyle
                          :allegation-subject? false
                          :source nil :unsourced? true}
               editor-p3)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
