(ns portal.map-bridge-test
  "`portal.map-bridge` — proves the tile layer is CONSUMED from
  `kotoba-lang/map` rather than reimplemented here (com-junkawasaki/root
  ADR-2607276000). The expected tile indices below are the standard
  slippy-map values, derivable from the zoom level alone, not copied from
  a previous run."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.map.constants :as mapc]
            [portal.map-bridge :as bridge]))

(def ^:private tokyo {:lat 35.681236 :lng 139.767125})

(deftest tile-xy-is-the-standard-slippy-index
  (testing "zoom 0 is a single tile covering the world"
    (is (= {:z 0 :x 0 :y 0} (bridge/tile-xy tokyo 0))))
  (testing "the origin sits at the boundary of the four zoom-1 quadrants"
    (is (= {:z 1 :x 1 :y 1} (bridge/tile-xy {:lat 0 :lng 0} 1)))
    (is (= {:z 1 :x 0 :y 0} (bridge/tile-xy {:lat 45 :lng -90} 1)))
    (is (= {:z 1 :x 1 :y 1} (bridge/tile-xy {:lat -45 :lng 90} 1))))
  (testing "Tokyo at zoom 12 — x = floor(2^12 · (lng+180)/360)"
    (let [{:keys [z x]} (bridge/tile-xy tokyo 12)]
      (is (= 12 z))
      (is (= (long (Math/floor (* 4096 (/ (+ 139.767125 180.0) 360.0)))) x))))
  (testing "an invalid coordinate yields no tile at all"
    (is (nil? (bridge/tile-xy {:lat ##NaN :lng 139.0} 12)))
    (is (nil? (bridge/tile-xy nil 12)))))

(deftest tile-url-uses-the-library-template
  (testing "the default template is kotoba-lang/map's, not a local copy"
    (let [{:keys [z x y]} (bridge/tile-xy tokyo 12)
          expected (-> mapc/default-tile-url
                       (.replace "{z}" (str z))
                       (.replace "{x}" (str x))
                       (.replace "{y}" (str y)))]
      (is (= expected (bridge/tile-url tokyo 12)))))
  (testing "an operator may supply their own tile server"
    (is (= "https://tiles.example.test/0/0/0.png"
           (bridge/tile-url tokyo 0 "https://tiles.example.test/{z}/{x}/{y}.png"))))
  (is (nil? (bridge/tile-url {:lat ##Inf :lng 0} 12))))

(deftest poi-tile-urls-dedupe-and-drop
  (let [pois [{:id "a" :lat 35.681236 :lng 139.767125}
              {:id "b" :lat 35.685175 :lng 139.752799}
              {:id "broken" :lat nil :lng nil}]]
    (is (= 1 (count (bridge/poi-tile-urls pois 0)))
        "two nearby POIs share one zoom-0 tile, and the broken one contributes none")
    (is (empty? (bridge/poi-tile-urls [{:id "broken" :lat nil :lng nil}] 12)))))
