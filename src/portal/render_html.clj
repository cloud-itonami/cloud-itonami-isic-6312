(ns portal.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`portal.operation` -> `portal.policy` (PortalGovernor) -> `portal.store`)
  through a scenario adapted directly from this repo's own `portal.sim`
  demo driver (`clojure -M:dev:run`, confirmed correct against the real
  seeded listing/source/placement/contract directory in `portal.store/
  demo-data` before this file was written -- unlike
  `cloud-itonami-isic-851`'s `schoolops.sim`, this repo's own sim driver
  uses ids (li-100/li-200, src-gov1/src-cc1/src-news1, tenant-basic) that
  DO exist in `portal.store/demo-data`, and its own printed dispositions
  (op1 commit / op2..op5 hold / op6..op7 escalate-then-approve) match a
  live run byte-for-byte, so it was safe to mine directly rather than
  author from scratch), trimmed to a representative subset and rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verified
  by diffing two consecutive runs).

  Deviation from the shared harness template's ADAPT 2: this actor's
  PortalGovernor RBAC table (`portal.policy/permissions`) restricts each
  op to a DIFFERENT actor-role (:content-editor for :listing/publish,
  :ad-ops for :placement/feature, :trust-safety-officer for
  :takedown/request), so a single global `operator` context cannot drive
  every op in the scenario -- `exec!` below takes an explicit `context`
  argument instead of closing over one shared var. This is a real
  reflection of this actor's own RBAC shape, not an invented deviation.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [portal.store :as store]
            [portal.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness ---------------------------------

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "ts-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, mined from `portal.sim`'s own proven-correct
  scenario:

    - li-300: a public-domain-sourced listing publish, clean and
      high-confidence -> phase-3 AUTO-COMMIT, no human involved.
    - li-200 (seeded with `:allegation-subject? true`) featured to
      slot-home-3: source/scope are clean, but the sensitive-subject
      gate ALWAYS escalates regardless of confidence -> approved by a
      trust & safety officer -> commit.
    - li-100 correction/takedown request: `:takedown/request` NEVER
      auto-resolves, at any phase or confidence -> escalates ->
      approved -> commit (a real SSoT mutation via
      `:correction-apply`, not just a ledger entry).
    - li-400: the PortalCurator-LLM proposes a listing with NO source
      citation at all (feed dropout, `:unsourced? true`) ->
      `:source-provenance-gate` HARD-holds, never reaches a human.
    - li-500: a `:fair-use-excerpt`-sourced listing whose snippet
      exceeds the 400-char conservative excerpt cap ->
      `:license-scope-gate` HARD-holds, never reaches a human.
    - slot-home-2: a sponsored placement proposed with no disclosure
      label (`:no-disclosure? true` strips the label before it reaches
      the governor) -> `:disclosure-gate` HARD-holds, never reaches a
      human.

  Three DISTINCT HARD-hold reasons (source-provenance-gate,
  license-scope-gate, disclosure-gate) never reach a human; two
  distinct ALWAYS-escalate reasons (sensitive-subject, takedown-dispute)
  both do, and are approved. Returns the resulting store -- every field
  read by `render` below is real governor/store output, not a hand-typed
  copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        editor {:actor-id "ed-1" :actor-role :content-editor :phase 3}
        adops  {:actor-id "ao-1" :actor-role :ad-ops :phase 3}
        tsafe  {:actor-id "ts-1" :actor-role :trust-safety-officer :phase 3}]

    (exec! actor "li-300-publish"
           {:op :listing/publish :subject "li-300" :title "デモ公共ドメイン記事"
            :snippet "米国連邦機関の公表資料からの転載(デモ、公共ドメイン)。"
            :source-id "src-gov1" :category :public-affairs :allegation-subject? false
            :source {:class :public-domain :ref "usc-17-105:demo"}}
           editor)

    (exec! actor "slot-home-3-feature"
           {:op :placement/feature :subject "slot-home-3" :listing-id "li-200"
            :sponsored? false :disclosure-label nil}
           adops)
    (approve! actor "slot-home-3-feature")

    (exec! actor "li-100-takedown"
           {:op :takedown/request :subject "li-100" :disputed-field :title
            :claim "デモ公共ドメイン記事(訂正済み)"}
           tsafe)
    (approve! actor "li-100-takedown")

    (exec! actor "li-400-publish"
           {:op :listing/publish :subject "li-400" :title "デモ記事C"
            :snippet "出典不明のデモ記事。" :source-id "src-cc1" :category :lifestyle
            :allegation-subject? false
            :source {:class :cc-attribution :ref "cc-by-4:demo"} :unsourced? true}
           editor)

    (exec! actor "li-500-publish"
           {:op :listing/publish :subject "li-500" :title "デモ記事D(長文抜粋)"
            :snippet (apply str (repeat 500 "あ")) :source-id "src-news1"
            :category :news :allegation-subject? false
            :source {:class :fair-use-excerpt :ref "usc-17-107:demo"}}
           editor)

    (exec! actor "slot-home-2-feature"
           {:op :placement/feature :subject "slot-home-2" :listing-id "li-100"
            :sponsored? true :disclosure-label "Sponsored" :no-disclosure? true}
           adops)
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :policy-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      (= :approval-rejected (:t f)) "<span class=\"err\">approval rejected</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- listing-row [ledger {:keys [id title source-id category status allegation-subject?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc title) (esc source-id) (esc (name (or category :n-a)))
          (esc (name (or status :n-a)))
          (if allegation-subject? "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README, `portal.policy`/`portal.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately hand-
  ;; described rather than derived from a live run.
  ["        <tr><td><code>:listing/publish</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &amp; high-confidence</span> &middot; <span class=\"critical\">source-provenance-gate / license-scope-gate HARD</span></td></tr>"
   "        <tr><td><code>:placement/feature</code></td><td><span class=\"ok\">phase-3 auto-commit when clean</span> &middot; <span class=\"critical\">disclosure-gate HARD</span> (sponsored w/o label) &middot; <span class=\"warn\">sensitive-subject listings ALWAYS escalate</span></td></tr>"
   "        <tr><td><code>:report/query</code></td><td><span class=\"muted\">governed read, no SSoT write</span> &middot; <span class=\"critical\">licensed-disclosure HARD</span> (tier over-disclosure)</td></tr>"
   "        <tr><td><code>:takedown/request</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase, any confidence</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        listings (store/all-listings db)
        listing-rows (str/join "\n" (map (partial listing-row ledger) listings))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6312 &middot; web portals</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Web portals (ISIC 6312) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · curates &amp; discloses content only, never handles money movement</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Listings (committed to the SSoT)</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>portal.store</code> via <code>portal.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly. HARD-held publish attempts (li-400, li-500) never reach this table — see the audit ledger below.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Listing</th><th>Title</th><th>Source</th><th>Category</th><th>Status</th><th>Allegation subject?</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     listing-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (PortalGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Source provenance and excerpt scope are checked independently of the LLM's own stated confidence; sponsored placements and sensitive-subject content route through human review before anything is disclosed.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold, escalation and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-listings db)) "listings in store )")))
