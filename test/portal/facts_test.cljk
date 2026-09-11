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

;; ───────────────────────── map slice (POI) ─────────────────────────

(deftest poi-legal-bases-are-well-formed-and-cited
  (doseq [{:keys [id name gate basis url]} facts/poi-legal-bases]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? gate))
      (is (string? basis))
      (is (and (string? url) (re-find #"^https://" url))
          "every POI gate basis must be citable to a real published source"))))

(deftest each-poi-gate-has-at-least-two-independent-bases
  (let [by-gate (group-by :gate facts/poi-legal-bases)]
    (is (= #{:entity-verification-gate :residential-privacy-gate} (set (keys by-gate))))
    (doseq [[gate entries] by-gate]
      (testing (str gate)
        (is (<= 2 (count entries))
            "a HARD gate resting on a single citation is a gate resting on one reading of one law")))))

(deftest lei-verification-accepts-only-issued
  (is (facts/lei-verified? {:lei "X" :status :issued}))
  (is (not (facts/lei-verified? {:lei "X" :status :lapsed})))
  (is (not (facts/lei-verified? {:lei "X" :status :retired})))
  (is (not (facts/lei-verified? {:lei "X" :status nil})))
  (is (not (facts/lei-verified? nil)) "an unresolved LEI is not verified"))

(deftest commercial-categories-assert-a-business-exists
  (is (facts/commercial-poi-category? :retail))
  (is (facts/commercial-poi-category? :restaurant))
  (is (not (facts/commercial-poi-category? :park))
      "a park claims no legal entity, so it is not gated on one")
  (is (not (facts/commercial-poi-category? :residence)))
  (is (not (facts/commercial-poi-category? nil))))

(deftest poi-coverage-states-what-is-not-verified
  (let [c (facts/poi-coverage)]
    (is (= 2 (:gate-count c)))
    (is (= (count facts/poi-legal-bases) (:legal-basis-count c)))
    (is (= #{:entity-verification-gate :residential-privacy-gate} (:gates c)))
    (is (re-find #"NOT verified" (:note c))
        "the coverage note must name the unverified surface (operator-asserted ISIC), not just the covered one")))
