(ns portal.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the PortalGovernor's licensed-disclosure
  gate approved for the caller's contract tier (see `:report/query`). This
  namespace only renders the approved columns, so a disclosure can never
  exceed the licensed tier."
  (:require [portal.store :as store]))

(defn render-listing
  "Render one listing's report over exactly `columns` (already governor-
  approved)."
  [db listing-id columns]
  (let [li (store/listing db listing-id)
        cell (fn [col] (if (= col :listing-id) listing-id (get li col)))]
    (into {} (map (juxt identity cell)) columns)))
