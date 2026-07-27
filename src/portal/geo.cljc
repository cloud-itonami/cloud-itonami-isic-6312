(ns portal.geo
  "Geo primitives for the map slice of this portal actor — coordinate
  validation, great-circle distance, service-area containment, radius
  search, and the normalized Web Mercator projection the operator console
  plots POI pins with.

  This namespace deliberately owns NO rendering. Tiles, globe meshes,
  projection-mode selection and MVT decoding all live in
  `kotoba-lang/map` (the pure-`.cljc` port of the KAMI WebGPU map
  renderer), which this repo consumes rather than reimplements — see
  com-junkawasaki/root ADR-2607276000 (`描画は kotoba-lang/map を消費し
  新 renderer を作らない`). Concretely, `clamp-lat` and the Web Mercator
  latitude bound come from `kotoba.map.projection` / `kotoba.map.constants`
  here, and `portal.map-bridge` reaches the tile-URL side of that library.

  On Earth radius: `kotoba.map.constants/earth-radius-m` is the WGS84
  EQUATORIAL radius (6378137.0 m) — correct for the globe geometry that
  constant was ported for, but 0.34% too large for great-circle surface
  distance. Radius search here uses the IUGG mean radius R1 instead
  (`wgs84-mean-radius-km`), which is the right constant for haversine.
  Both are stated rather than silently reconciled."
  (:require [kotoba.map.constants :as mapc]
            [kotoba.map.projection :as mapproj]))

(def wgs84-mean-radius-km
  "IUGG arithmetic mean radius R1 = (2a + b)/3 for WGS84, in km. The
  standard constant for haversine surface distance — NOT the same as
  `kotoba.map.constants/earth-radius-m` (equatorial, used for globe
  rendering)."
  6371.0088)

(def max-mercator-lat
  "Web Mercator latitude bound, re-exported from `kotoba.map.constants` so
  this namespace states one source of truth for it rather than hardcoding
  85.05112877980659 again."
  mapc/max-lat)

;; ───────────────────────── validation ─────────────────────────

(defn- finite-num? [x]
  (and (number? x)
       #?(:clj  (let [d (double x)]
                  (and (not (Double/isNaN d)) (not (Double/isInfinite d))))
          :cljs (js/isFinite x))))

(defn valid-coord?
  "True when `p` carries a finite WGS84 latitude/longitude pair in range.
  Rejects nil, non-numeric, NaN/Infinity, and out-of-range values — the
  three shapes a bad geocode actually arrives in."
  [{:keys [lat lng]}]
  (and (finite-num? lat) (finite-num? lng)
       (<= -90.0 (double lat) 90.0)
       (<= -180.0 (double lng) 180.0)))

;; ───────────────────────── distance ─────────────────────────

(defn- radians [deg] (* (double deg) (/ Math/PI 180.0)))

(defn haversine-km
  "Great-circle distance in km between two `{:lat :lng}` points. Returns
  nil when either point is not a valid coordinate — callers must decide
  what an unmeasurable distance means rather than receive a plausible
  number."
  [a b]
  (when (and (valid-coord? a) (valid-coord? b))
    (let [dlat (radians (- (:lat b) (:lat a)))
          dlng (radians (- (:lng b) (:lng a)))
          la1  (radians (:lat a))
          la2  (radians (:lat b))
          h    (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
                  (* (Math/cos la1) (Math/cos la2)
                     (Math/sin (/ dlng 2)) (Math/sin (/ dlng 2))))]
      (* 2 wgs84-mean-radius-km (Math/asin (Math/sqrt (min 1.0 h)))))))

(defn within-radius?
  "True when `p` is within `radius-km` of `center`. False (never true) when
  either coordinate is invalid."
  [center p radius-km]
  (boolean (when-let [d (haversine-km center p)]
             (<= d (double radius-km)))))

;; ───────────────────────── service area ─────────────────────────

(def default-service-area
  "The operator's declared service bounding box. Defaults to the whole
  WGS84 range — an operator narrows this to the area they can actually
  verify POIs in, and `portal.policy`'s geo-bounds-gate then HARD-rejects
  pins outside it. This is a structural operator control, not a legal
  boundary."
  {:min-lat -90.0 :max-lat 90.0 :min-lng -180.0 :max-lng 180.0})

(defn in-service-area?
  "True when `p` is a valid coordinate inside `area` (inclusive bounds)."
  [{:keys [min-lat max-lat min-lng max-lng] :as area} p]
  (and (valid-coord? p)
       (some? area)
       (<= (double min-lat) (double (:lat p)) (double max-lat))
       (<= (double min-lng) (double (:lng p)) (double max-lng))))

;; ───────────────────────── search ─────────────────────────

(defn nearby
  "POIs within `radius-km` of `center`, nearest first, each with the
  measured `:distance-km` attached. POIs with invalid coordinates are
  dropped (they can never be shown to be near anything) rather than
  silently ranked last."
  [pois center radius-km]
  (->> pois
       (keep (fn [poi]
               (when-let [d (haversine-km center poi)]
                 (when (<= d (double radius-km))
                   (assoc poi :distance-km d)))))
       (sort-by (juxt :distance-km :id))
       vec))

;; ───────────────────────── projection (plotting only) ─────────────────

(defn mercator-unit-xy
  "Normalized Web Mercator position of `p` as `[x y]` in the unit square,
  x/y increasing east/south. Latitude is clamped through
  `kotoba.map.projection/clamp-lat` (kotoba-lang/map) rather than clamped
  again here. Used only to plot pins on the static operator console —
  actual map rendering is kotoba-lang/map's job."
  [p]
  (when (valid-coord? p)
    (let [lat (mapproj/clamp-lat (double (:lat p)))
          lng (double (:lng p))
          x   (/ (+ lng 180.0) 360.0)
          s   (Math/sin (radians lat))
          y   (- 0.5 (/ (Math/log (/ (+ 1.0 s) (- 1.0 s))) (* 4.0 Math/PI)))]
      [x y])))

(defn plot-xy
  "Project `p` into a `[0,width] × [0,height]` box fitted to `area`, for
  the console's inline SVG pin plot. Returns nil for an unplottable point."
  [area p width height]
  (when (and (in-service-area? area p) (valid-coord? p))
    (let [[x y]   (mercator-unit-xy p)
          [x0 y0] (mercator-unit-xy {:lat (:max-lat area) :lng (:min-lng area)})
          [x1 y1] (mercator-unit-xy {:lat (:min-lat area) :lng (:max-lng area)})
          dx      (- x1 x0)
          dy      (- y1 y0)]
      (when (and (pos? dx) (pos? dy))
        [(* width (/ (- x x0) dx))
         (* height (/ (- y y0) dy))]))))
