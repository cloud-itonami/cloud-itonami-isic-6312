(ns portal.llm-test
  "PortalCurator-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [portal.store :as store]
            [portal.llm :as llm]))

(deftest publish-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :listing/publish :subject "li-900" :title "X" :snippet "Y"
                         :source-id "src-gov1" :category :public-affairs
                         :allegation-subject? false
                         :source {:class :public-domain :ref "demo"}})]
    (is (= :listing-upsert (:effect p)))
    (is (= {:class :public-domain :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-publish-proposal-carries-nil-source
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :listing/publish :subject "li-900" :title "X" :snippet "Y"
                           :source-id "src-gov1" :category :public-affairs
                           :allegation-subject? false
                           :source {:class :public-domain :ref "demo"} :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence — proves source-provenance cannot rely on confidence as a proxy"))))

(deftest feature-proposal-no-disclosure-flag-strips-label
  (testing "the LLM layer does not filter — the injected failure mode reaches the proposal, governor's job to reject it"
    (let [db (store/seed-db)
          p (llm/infer db {:op :placement/feature :subject "slot-x" :listing-id "li-100"
                           :sponsored? true :disclosure-label "Sponsored" :no-disclosure? true})]
      (is (nil? (get-in p [:value :disclosure-label])))
      (is (true? (get-in p [:value :sponsored?])))
      (is (>= (:confidence p) 0.85)))))

(deftest report-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :report/query :subject "li-100"})
        greedy (llm/infer db {:op :report/query :subject "li-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:snippet :allegation-subject?} (:columns greedy)))))

(deftest takedown-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :takedown/request :subject "li-100" :disputed-field :title
                         :claim "訂正案"})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9) "takedown drafts are claims pending human verification, never auto-confident")))
