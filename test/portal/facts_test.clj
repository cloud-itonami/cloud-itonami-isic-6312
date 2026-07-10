(ns portal.facts-test
  "The R0 source-provenance catalog is the whole ground truth for the
  source-provenance gate — these tests guard its own internal honesty
  (every class it advertises is actually backed by a catalog entry, no
  duplicate/aspirational entries)."
  (:require [clojure.test :refer [deftest is testing]]
            [portal.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name class basis]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? class))
      (is (string? basis)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :public-domain))
  (is (facts/class-allowed? :cc-attribution))
  (is (facts/class-allowed? :fair-use-excerpt))
  (is (facts/class-allowed? :licensed-syndication))
  (is (not (facts/class-allowed? :scraped)))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? nil))))

(deftest licensed-syndication-class-recognized
  (is (facts/licensed-syndication-class? :licensed-syndication))
  (is (not (facts/licensed-syndication-class? :public-domain))))

(deftest excerpt-capped-class-recognized
  (is (facts/excerpt-capped-class? :fair-use-excerpt))
  (is (not (facts/excerpt-capped-class? :cc-attribution)))
  (is (not (facts/excerpt-capped-class? :public-domain))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (= 3 (count (:free-legal-bases c))) "exactly the 3 real, free, citable legal bases")))
