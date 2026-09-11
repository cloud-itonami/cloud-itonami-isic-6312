(ns portal.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the PortalGovernor's licensed-disclosure
  gate approved for the caller's contract tier (see `:report/query`). This
  namespace only renders the approved columns, so a disclosure can never
  exceed the licensed tier."
  (:require [portal.geo :as geo]
            [portal.store :as store]))

(defn render-listing
  "Render one listing's report over exactly `columns` (already governor-
  approved)."
  [db listing-id columns]
  (let [li (store/listing db listing-id)
        cell (fn [col] (if (= col :listing-id) listing-id (get li col)))]
    (into {} (map (juxt identity cell)) columns)))

(defn render-poi-search
  "Render a governed radius search over exactly `columns` (already
  approved by the licensed-disclosure gate for the caller's tier).

  Only `:live` POIs are searchable — a POI that was held or retired is
  never disclosed, so a rejected pin cannot leak through the read path
  after the write path refused it."
  [db center radius-km columns]
  (let [hits (geo/nearby (filter #(= :live (:status %)) (store/all-pois db))
                         center radius-km)
        cell (fn [poi col] (if (= col :poi-id) (:id poi) (get poi col)))]
    (mapv (fn [poi] (into {} (map (juxt identity (partial cell poi))) columns))
          hits)))
