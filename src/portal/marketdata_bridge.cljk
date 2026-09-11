(ns portal.marketdata-bridge
  "Optional bridge: sources a portal listing from a sibling
  `cloud-itonami-isic-6311` (multi-asset market-data actor) GOVERNED
  OperationActor, instead of a hand-authored third-party source. NOT a
  compile-time dependency of `portal.*` core — a caller wires this in
  explicitly (same optional-injectable seam as `cloud-itonami-isic-6311`'s
  own `kotoba.securities.pricing` bridge into `kotoba-lang/securities`).

  The market-data query goes through isic-6311's OWN MarketDataGovernor
  (tolerance-gate, source-provenance-gate, licensed-disclosure, halted-
  instrument gate, ...) BEFORE this namespace ever sees a result. If that
  governed query does not COMMIT (held, or interrupted pending human
  approval), this bridge does NOT fabricate a listing — it emits a request
  with `:source nil` (unsourced), which `portal.policy`'s OWN
  source-provenance-gate then independently HARD-rejects too. A market-
  data-sourced listing is never special-cased past portal's governance,
  only ever composed with it — defense in depth, not a bypass."
  (:require [langgraph.graph :as g]
            [marketdata.report :as md-report]))

(def default-columns
  "Portal only ever asks for the :tier/basic column set on the upstream
  isic-6311 query — the same discipline as any other licensed-disclosure
  consumer: request no more than what's actually rendered downstream."
  [:instrument-id :symbol :asset-class :price :currency :as-of])

(defn market-quote-listing-request
  "Runs a `:disclosure/query` against a `cloud-itonami-isic-6311`
  OperationActor (`md-actor`, built via `marketdata.operation/build`) for
  `instrument-id`, then shapes the result into a `portal.llm/infer`-
  compatible `:listing/publish` request map.

  `md-store`   — the same `marketdata.store/Store` `md-actor` was built
                 over (needed to read back the committed quote).
  `md-context` — the isic-6311 subscriber context for the query
                 (`{:actor-id .. :actor-role :subscriber :tenant ..}`).
  `thread-id`  — a unique langgraph thread id for this isic-6311 run.

  Returns a request map ALWAYS — `:source` is `nil` (unsourced) whenever
  the underlying market-data query did not commit, so `portal.policy`'s own
  source-provenance-gate rejects it independently. Never silently
  publishes stale/wrong/unlicensed data."
  [md-actor md-store instrument-id md-context thread-id]
  (let [res (g/run* md-actor
                     {:request {:op :disclosure/query :subject instrument-id
                                :instrument-id instrument-id}
                      :context md-context}
                     {:thread-id thread-id})
        committed? (= :commit (get-in res [:state :disposition]))
        q          (when committed? (md-report/render-quote md-store instrument-id default-columns))]
    {:op :listing/publish
     :subject (str "market-" instrument-id)
     :title (str "市場データ: " (if committed? (:symbol q) instrument-id))
     :snippet (if committed?
                (str (:symbol q) " " (:price q) " " (name (:currency q))
                     " (as of " (:as-of q) ", via cloud-itonami-isic-6311)")
                "市場データ取得未確定(isic-6311 側で開示未確定 — 未開示のため掲載しない)")
     :source-id "src-md6311"
     :category :market-data
     :allegation-subject? false
     :source (when committed?
               {:class :cloud-itonami-market-data-feed
                :ref (str "cloud-itonami-isic-6311:" instrument-id)})}))
