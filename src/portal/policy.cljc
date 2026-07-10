(ns portal.policy
  "PortalGovernor — the independent compliance layer that earns the
  PortalCurator-LLM the right to publish a listing, feature a placement,
  serve a report, or resolve a takedown. The LLM has no notion of
  copyright-license scope, native-advertising disclosure law, or a
  client's disclosure entitlement, so this MUST be a separate system able
  to *reject* a proposal and fall back to HOLD (publish/feature/disclose
  nothing) — this actor's analog of `cloud-itonami-isic-8291`'s
  DisclosureGovernor and `cloud-itonami-isic-6311`'s MarketDataGovernor.

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                  — does actor-role have permission for op?
    2. source-provenance-gate — does the listing cite an allowed license
                                class, and — for `:licensed-syndication` —
                                an ACTIVE `content-license`?
    3. license-scope-gate     — for a `:fair-use-excerpt`-sourced listing,
                                does the snippet stay within the
                                conservative excerpt-length ceiling? (this
                                actor's domain-unique HARD check, no analog
                                in any sibling actor — grounded in 17 U.S.C.
                                §107's excerpt/commentary doctrine, see
                                `portal.facts`)
    4. disclosure-gate        — a sponsored placement without an explicit
                                `:disclosure-label` is rejected outright
                                (FTC native-advertising disclosure, 16 CFR
                                Part 255 — this actor's second domain-
                                unique HARD check)
    5. licensed-disclosure    — is there an active advertiser contract, and
                                does the requested report stay within its
                                tier?
    6. confidence floor       — LLM confidence below threshold → escalate.
    7. sensitive-subject gate — the listing concerns a real named
                                individual/company under allegation →
                                always escalate, regardless of confidence
                                (defamation-risk analog to sibling actors'
                                high-stakes/halted-instrument/hazardous-
                                duty gates).
    8. takedown requests      — a rightsholder/subject takedown request
                                NEVER auto-resolves, at any confidence, any
                                phase."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [portal.facts :as facts]
            [portal.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:content-editor        #{:listing/publish}
   :ad-ops                #{:placement/feature}
   :trust-safety-officer  #{:takedown/request}
   :advertiser-client     #{:report/query}})

(def tier-columns
  "For `:report/query` — the columns each licensed advertiser-contract tier
  may see. Anything beyond this is over-disclosure (licensed-disclosure
  violation), the portal analog of `dossier`/`marketdata`'s tier tables."
  (let [base #{:listing-id :title :category :status :as-of}
        analytics-extra #{:source-id}
        audit-extra #{:snippet :allegation-subject?}]
    {:tier/basic     base
     :tier/analytics (into base analytics-extra)
     :tier/audit     (into base (into analytics-extra audit-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- source-provenance-violations
  [{:keys [op]} proposal st]
  (when (= op :listing/publish)
    (let [src (:source proposal)
          class (:class src)]
      (cond
        (or (nil? src) (not (facts/class-allowed? class)))
        [{:rule :source-provenance-gate
          :detail (str "出典が無いか許可されたライセンスクラスでない: " (pr-str src))}]

        (facts/licensed-syndication-class? class)
        (let [lic (store/content-license st (:license-id src))]
          (when (or (nil? lic) (not (:active? lic)))
            [{:rule :source-provenance-gate
              :detail (str "有効な content-license が無い: license-id=" (:license-id src))}]))

        :else nil))))

(defn- license-scope-violations
  [{:keys [op]} proposal]
  (when (= op :listing/publish)
    (let [class (get-in proposal [:source :class])
          snippet (get-in proposal [:value :snippet] "")]
      (when (and (facts/excerpt-capped-class? class)
                 (> (count snippet) facts/fair-use-excerpt-max-chars))
        [{:rule :license-scope-gate
          :detail (str "fair-use-excerpt 出典なのに抜粋が上限超過: "
                       (count snippet) " > " facts/fair-use-excerpt-max-chars " 文字")}]))))

(defn- disclosure-violations
  [{:keys [op]} proposal]
  (when (= op :placement/feature)
    (let [{:keys [sponsored? disclosure-label]} (:value proposal)]
      (when (and sponsored? (str/blank? (str disclosure-label)))
        [{:rule :disclosure-gate
          :detail "スポンサード配置なのに開示ラベル(:disclosure-label)が無い"}]))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :report/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- sensitive-subject?
  "True when the op's target listing (existing or newly-proposed) is
  flagged as concerning a real named individual/company under allegation."
  [{:keys [op subject]} proposal st]
  (case op
    :listing/publish (boolean (get-in proposal [:value :allegation-subject?]))
    :placement/feature (boolean (:allegation-subject? (store/listing st (get-in proposal [:value :listing-id]))))
    (boolean (:allegation-subject? (store/listing st subject)))))

(defn check
  "Censors a PortalCurator-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :sensitive? bool
    :hard? bool :takedown? bool}.

   - :hard?       — at least one HARD violation (source-provenance-gate/
                    license-scope-gate/disclosure-gate/licensed-
                    disclosure). Forces HOLD; a human cannot override.
   - :escalate?   — soft: low confidence, sensitive-subject listing, OR a
                    takedown request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-serve."
  [request context proposal st]
  (let [hard        (into []
                          (concat (rbac-violations request context)
                                  (source-provenance-violations request proposal st)
                                  (license-scope-violations request proposal)
                                  (disclosure-violations request proposal)
                                  (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        sensitive?  (sensitive-subject? request proposal st)
        takedown?   (= :takedown/request (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not sensitive?) (not takedown?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? sensitive? takedown?))
     :sensitive?   sensitive?
     :takedown?    takedown?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
