(ns portal.map-bridge
  "Tile-layer bridge to `kotoba-lang/map` — the slippy-tile coordinates and
  URLs an operator's own map front-end needs to actually draw the POIs this
  actor governs.

  This exists so the map slice never grows its own renderer
  (com-junkawasaki/root ADR-2607276000): the tile URL template, the Web
  Mercator latitude clamp and the zoom clamp all come from
  `kotoba.map.constants` / `kotoba.map.projection` / `kotoba.map.tile-url`,
  and this namespace only converts a governed POI's coordinate into the
  `{z}/{x}/{y}` triple those functions expect.

  The operator console (`portal.render-html`) deliberately does NOT use
  this: that page is a static, self-contained sample and must not fetch
  third-party tiles. A real deployment does — that is what this is for."
  (:require [kotoba.map.constants :as mapc]
            [kotoba.map.projection :as mapproj]
            [kotoba.map.tile-url :as maptile]
            [portal.geo :as geo]))

(defn tile-xy
  "Slippy-map tile column/row containing `p` at integer zoom `z`. Returns
  nil for an invalid coordinate rather than a tile that means nothing.
  Latitude is clamped through `kotoba.map.projection/clamp-lat`."
  [p z]
  (when (geo/valid-coord? p)
    (let [z*    (long (mapproj/clamp-zoom z))
          n     (Math/pow 2 z*)
          [x y] (geo/mercator-unit-xy p)]
      {:z z*
       :x (long (Math/floor (* n x)))
       :y (long (Math/floor (* n y)))})))

(defn tile-url
  "Tile URL for the tile containing `p` at zoom `z`, built with
  `kotoba.map.tile-url/template-url`. `template` defaults to
  `kotoba.map.constants/default-tile-url` (OpenStreetMap). Returns nil
  when the coordinate is invalid.

  The operator supplies their own template when they have their own tile
  server or attribution obligations — this actor asserts no right to
  redistribute anyone's tiles."
  ([p z] (tile-url p z mapc/default-tile-url))
  ([p z template]
   (when-let [{:keys [z x y]} (tile-xy p z)]
     (maptile/template-url template z x y))))

(defn poi-tile-urls
  "Tile URLs for a collection of governed POIs at zoom `z`, deduplicated —
  the set of tiles a front-end must fetch to show them. POIs with invalid
  coordinates are dropped (they were never publishable anyway)."
  ([pois z] (poi-tile-urls pois z mapc/default-tile-url))
  ([pois z template]
   (into [] (distinct) (keep #(tile-url % z template) pois))))
