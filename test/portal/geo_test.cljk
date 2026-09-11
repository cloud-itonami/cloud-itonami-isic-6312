(ns portal.geo-test
  "`portal.geo` primitives. The distances asserted here are checked against
  closed-form values on a sphere of radius `wgs84-mean-radius-km`, not
  against numbers copied out of a previous run — one degree of longitude at
  the equator is R·π/180 and half a great circle is R·π, so both are
  independently derivable."
  (:require [clojure.test :refer [deftest is testing]]
            [portal.geo :as geo]))

(def ^:private tokyo {:lat 35.681236 :lng 139.767125})

(deftest valid-coord-rejects-unusable-input
  (testing "nil / missing / non-numeric"
    (is (not (geo/valid-coord? nil)))
    (is (not (geo/valid-coord? {})))
    (is (not (geo/valid-coord? {:lat 35.0})))
    (is (not (geo/valid-coord? {:lat "35.0" :lng 139.0}))))
  (testing "NaN and infinity — the shapes a failed geocode actually returns"
    (is (not (geo/valid-coord? {:lat ##NaN :lng 139.0})))
    (is (not (geo/valid-coord? {:lat 35.0 :lng ##Inf})))
    (is (not (geo/valid-coord? {:lat ##-Inf :lng 139.0}))))
  (testing "out of WGS84 range"
    (is (not (geo/valid-coord? {:lat 91.0 :lng 0.0})))
    (is (not (geo/valid-coord? {:lat 0.0 :lng 181.0}))))
  (testing "valid, including the boundary values"
    (is (geo/valid-coord? tokyo))
    (is (geo/valid-coord? {:lat 90.0 :lng 180.0}))
    (is (geo/valid-coord? {:lat -90.0 :lng -180.0}))
    (is (geo/valid-coord? {:lat 0 :lng 0}) "integers, not just doubles")))

(deftest haversine-matches-closed-form
  (testing "one degree of longitude at the equator = R·π/180"
    (let [expected (* geo/wgs84-mean-radius-km (/ Math/PI 180.0))]
      (is (< (Math/abs (- (geo/haversine-km {:lat 0 :lng 0} {:lat 0 :lng 1})
                          expected))
             1e-9))))
  (testing "antipodal along the equator = R·π"
    (let [expected (* geo/wgs84-mean-radius-km Math/PI)]
      (is (< (Math/abs (- (geo/haversine-km {:lat 0 :lng 0} {:lat 0 :lng 180})
                          expected))
             1e-6))))
  (testing "identical points are zero, not a rounding artifact"
    (is (zero? (geo/haversine-km tokyo tokyo))))
  (testing "an unmeasurable distance is nil, never a plausible number"
    (is (nil? (geo/haversine-km tokyo {:lat ##NaN :lng 0})))
    (is (nil? (geo/haversine-km tokyo nil)))))

(deftest within-radius-never-true-for-invalid-input
  (is (geo/within-radius? tokyo {:lat 35.685175 :lng 139.752799} 5.0))
  (is (not (geo/within-radius? tokyo {:lat 34.0 :lng 135.0} 5.0)))
  (is (not (geo/within-radius? tokyo {:lat ##NaN :lng 139.0} 1e9))
      "an invalid point is not 'near everything' even at an absurd radius"))

(deftest in-service-area-bounds
  (let [kanto {:min-lat 35.0 :max-lat 36.5 :min-lng 139.0 :max-lng 140.5}]
    (is (geo/in-service-area? kanto tokyo))
    (is (not (geo/in-service-area? kanto {:lat 34.6937 :lng 135.5023})))
    (is (geo/in-service-area? kanto {:lat 35.0 :lng 139.0}) "inclusive bounds")
    (is (not (geo/in-service-area? kanto {:lat ##NaN :lng 139.5})))
    (is (geo/in-service-area? geo/default-service-area tokyo)
        "the default area admits any valid coordinate")))

(deftest nearby-ranks-and-drops
  (let [pois [{:id "far"     :lat 34.6937 :lng 135.5023}
              {:id "near"    :lat 35.685175 :lng 139.752799}
              {:id "nearest" :lat 35.681300 :lng 139.767200}
              {:id "broken"  :lat nil :lng nil}]
        hits (geo/nearby pois tokyo 10.0)]
    (is (= ["nearest" "near"] (mapv :id hits)) "nearest first, far/broken excluded")
    (is (every? :distance-km hits) "the measured distance is attached, not recomputed by callers")
    (is (apply <= (map :distance-km hits)))
    (is (not-any? #(= "broken" (:id %)) (geo/nearby pois tokyo 1e9))
        "an invalid coordinate is dropped, not ranked last")))

(deftest mercator-projection
  (testing "the origin lands at the center of the unit square"
    (let [[x y] (geo/mercator-unit-xy {:lat 0 :lng 0})]
      (is (< (Math/abs (- x 0.5)) 1e-12))
      (is (< (Math/abs (- y 0.5)) 1e-12))))
  (testing "antimeridians are the unit-square edges"
    (is (< (Math/abs (- (first (geo/mercator-unit-xy {:lat 0 :lng -180})) 0.0)) 1e-12))
    (is (< (Math/abs (- (first (geo/mercator-unit-xy {:lat 0 :lng 180})) 1.0)) 1e-12)))
  (testing "y increases southward"
    (let [[_ north] (geo/mercator-unit-xy {:lat 45 :lng 0})
          [_ south] (geo/mercator-unit-xy {:lat -45 :lng 0})]
      (is (< north 0.5))
      (is (> south 0.5))))
  (testing "latitude beyond the Mercator bound is clamped, not NaN"
    (let [[_ y] (geo/mercator-unit-xy {:lat 89.9 :lng 0})]
      (is (and (number? y) (not (Double/isNaN y)) (not (Double/isInfinite y))))))
  (is (nil? (geo/mercator-unit-xy {:lat ##NaN :lng 0}))))

(deftest plot-xy-fits-the-box
  (let [kanto {:min-lat 35.0 :max-lat 36.5 :min-lng 139.0 :max-lng 140.5}
        [x y] (geo/plot-xy kanto tokyo 800.0 600.0)]
    (is (<= 0.0 x 800.0))
    (is (<= 0.0 y 600.0))
    (is (nil? (geo/plot-xy kanto {:lat 34.6937 :lng 135.5023} 800.0 600.0))
        "a point outside the area has no position in the box")))
