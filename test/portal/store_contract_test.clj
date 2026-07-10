(ns portal.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a
  rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [portal.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "デモ連邦機関(米国、デモ)" (:name (store/source s "src-gov1"))))
      (is (= :public-domain (:license-class (store/source s "src-gov1"))))
      (is (= "デモ記事A" (:title (store/listing s "li-100"))))
      (is (= :live (:status (store/listing s "li-100"))))
      (is (true? (:allegation-subject? (store/listing s "li-200"))))
      (is (= "li-100" (:listing-id (store/placement s "slot-home-1"))))
      (is (true? (:active? (store/content-license s "lic-synd1"))))
      (is (false? (:active? (store/content-license s "lic-expired"))))
      (is (= 2 (count (store/all-listings s))))
      ;; 5 = 4 original demo sources + src-md6311 (cloud-itonami-isic-6311
      ;; market-data-feed bridge source, portal.marketdata-bridge).
      (is (= 5 (count (store/all-sources s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "listing upsert replaces the record"
        (store/commit-record! s {:effect :listing-upsert
                                 :value {:id "li-100" :title "更新後デモ記事A" :snippet "更新後"
                                         :source-id "src-gov1" :category :public-affairs
                                         :status :live :as-of "2026-07-11T00:00:00Z"
                                         :allegation-subject? false}})
        (is (= "更新後デモ記事A" (:title (store/listing s "li-100")))))
      (testing "placement upsert commits a slot"
        (store/commit-record! s {:effect :placement-upsert
                                 :value {:slot-id "slot-home-9" :listing-id "li-100"
                                         :sponsored? true :disclosure-label "Sponsored"
                                         :as-of "2026-07-11T00:00:00Z"}})
        (is (= "li-100" (:listing-id (store/placement s "slot-home-9")))))
      (testing "correction-apply patches the target listing"
        (store/commit-record! s {:effect :correction-apply
                                 :value {:patch {:title "訂正済みタイトル"}}
                                 :path ["li-100"]})
        (is (= "訂正済みタイトル" (:title (store/listing s "li-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/analytics (:tier (store/contract s "tenant-acme"))))
      (is (true? (:active? (store/contract s "tenant-acme"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/listing s "nope")))
    (is (= [] (store/all-listings s)))
    (is (= [] (store/ledger s)))
    (store/with-listings s {"x" {:id "x" :title "X" :status :live}})
    (is (= "X" (:title (store/listing s "x"))))))
