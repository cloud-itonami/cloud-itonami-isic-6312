(ns portal.policy
  "PortalGovernor — the independent compliance layer that earns the
  PortalCurator-LLM the right to publish a listing, feature a placement,
  serve a report, or resolve a takedown. The LLM has no notion of
  copyright-license scope, native-advertising disclosure law, or a
  client's disclosure entitlement, so this MUST be a separate system able
  to *reject* a proposal and fall back to HOLD (publish/feature/disclose
  nothing) — this actor's analog of `cloud-itonami-isic-8291`'s
  DisclosureGovernor and `cloud-itonami-isic-6311`'s MarketDataGovernor.

  Eleven checks, in priority order. The first eight are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

  Checks 6-8 are the map slice (com-junkawasaki/root ADR-2607276000):
  publishing a pin asserts a business EXISTS and that a place is public,
  which are different assertions than publishing an article, and neither
  is something the PortalCurator-LLM can adjudicate.

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
                                does the requested report (`:report/query`)
                                or POI search (`:poi/search`) stay within
                                its tier?
    6. geo-bounds-gate        — does the POI pin / search center carry a
                                valid WGS84 coordinate inside the
                                operator's declared service area? (map
                                slice HARD check; structural operator
                                control, see `portal.geo`)
    7. entity-verification-gate — does a commercial POI's claimed legal
                                entity resolve to a registry record in an
                                accepted status, and does its ISIC code
                                agree with that record? (map slice HARD
                                check, ISO 17442 + FTC Act §5 — the
                                fabricated-business-listing defense)
    8. residential-privacy-gate — is the POI a private residence tied to a
                                named natural person? (map slice HARD
                                check, APPI 第2条第1項 / GDPR Art.4(1))
    9. confidence floor       — LLM confidence below threshold → escalate.
   10. sensitive-subject gate — the listing concerns a real named
                                individual/company under allegation →
                                always escalate, regardless of confidence
                                (defamation-risk analog to sibling actors'
                                high-stakes/halted-instrument/hazardous-
                                duty gates).
   11. takedown requests      — a rightsholder/subject takedown request
                                NEVER auto-resolves, at any confidence, any
                                phase."
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]
            [portal.facts :as facts]
            [portal.geo :as geo]
            [portal.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform. The map slice adds
  `:geo-editor` (POI curation) as a role distinct from `:content-editor`
  — publishing a pin asserts a place exists, which is a different
  competence and a different blast radius than publishing an article."
  {:content-editor        #{:listing/publish}
   :geo-editor            #{:poi/publish}
   :ad-ops                #{:placement/feature}
   :trust-safety-officer  #{:takedown/request}
   :advertiser-client     #{:report/query :poi/search}})

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

(def poi-tier-columns
  "For `:poi/search` — the POI columns each licensed advertiser-contract
  tier may see. `:lei` and `:isic-code` are the fleet join keys, so they
  sit at :tier/analytics and above: a basic-tier ad buyer gets a map, not
  a resolvable entity graph. `:source-id` and `:subject-name` are
  audit-tier only — `:subject-name` exists solely so a trust & safety
  audit can see WHY a residential POI was rejected, and must never be
  disclosed to an ad buyer."
  (let [base #{:poi-id :name :lat :lng :category :status :as-of :distance-km}
        analytics-extra #{:lei :isic-code}
        audit-extra #{:source-id :residential? :subject-name}]
    {:tier/basic     base
     :tier/analytics (into base analytics-extra)
     :tier/audit     (into base (into analytics-extra audit-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- source-provenance-violations
  "Applies to both publish ops: a POI pin needs a cited source class for
  exactly the same reason an article listing does — an uncited place is an
  unverifiable assertion about the world."
  [{:keys [op]} proposal st]
  (when (#{:listing/publish :poi/publish} op)
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
  "Applies to both disclosure ops. `:report/query` discloses listing
  columns, `:poi/search` discloses POI columns — same contract/tier
  discipline, different column table."
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (#{:report/query :poi/search} op)
    (let [table (if (= op :poi/search) poi-tier-columns tier-columns)
          c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get table (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

;; ───────────────────────── map slice gates ─────────────────────────
;; Three HARD checks the map slice adds (com-junkawasaki/root
;; ADR-2607276000). Each answers a question the PortalCurator-LLM
;; structurally cannot: does this coordinate mean anything, does the
;; business it claims exist, and is this place lawfully publishable.

(defn- geo-bounds-violations
  "A POI publish must carry a valid WGS84 coordinate inside the operator's
  declared service area; a POI search must center on one. An invalid or
  out-of-area coordinate is unverifiable — the operator has no way to
  confirm a place they cannot reach, so a pin is never dropped there.
  This is a structural operator control (the geo analog of the excerpt
  cap), not a legal boundary."
  [{:keys [op]} context proposal]
  (when (#{:poi/publish :poi/search} op)
    ;; both ops carry the coordinate under :value — a publish's pin
    ;; location, a search's center point.
    (let [area  (:service-area context geo/default-service-area)
          point (select-keys (:value proposal) [:lat :lng])]
      (cond
        (not (geo/valid-coord? point))
        [{:rule :geo-bounds-gate
          :detail (str "座標が不正(欠落/NaN/範囲外): " (pr-str point))}]

        (not (geo/in-service-area? area point))
        [{:rule :geo-bounds-gate
          :detail (str "サービス提供範囲外の座標: " (pr-str point) " area=" (pr-str area))}]

        :else nil))))

(defn- entity-verification-violations
  "A POI that asserts a business exists must name a legal entity that
  resolves to a registry record in an accepted status (`portal.facts`:
  ISO 17442 LEI + FTC Act §5). Two failure modes, both HARD:

    - a commercial-category POI with no LEI, or an LEI that does not
      resolve / is lapsed → a fabricated or stale business listing.
    - a resolved LEI whose registry ISIC code CONTRADICTS the code the
      POI claims → the entity is real but is being filed under the wrong
      industry, which silently corrupts every downstream join on
      `:company/lei`.

  Note what is NOT checked: an ISIC code on a POI with no LEI at all is
  operator-asserted and unverifiable here (`facts/poi-coverage` says so
  explicitly). This gate never invents verification it does not have."
  [{:keys [op]} proposal st]
  (when (= op :poi/publish)
    (let [{:keys [category lei isic-code]} (:value proposal)
          commercial? (facts/commercial-poi-category? category)
          record      (when lei (store/lei-entity st lei))]
      (cond
        (and commercial? (nil? lei))
        [{:rule :entity-verification-gate
          :detail (str "商用カテゴリ(" category ")の POI に法人識別子(:lei)が無い")}]

        (and lei (not (facts/lei-verified? record)))
        [{:rule :entity-verification-gate
          :detail (str "LEI がレジストリで検証できない(未登録/失効): lei=" lei
                       " status=" (pr-str (:status record)))}]

        (and record isic-code (:isic-code record)
             (not= isic-code (:isic-code record)))
        [{:rule :entity-verification-gate
          :detail (str "claimed ISIC " isic-code " がレジストリ記録 "
                       (:isic-code record) " と矛盾する: lei=" lei)}]

        :else nil))))

(defn- residential-privacy-violations
  "A POI flagged as a private residence AND tied to a named natural person
  is personal data (`portal.facts`: APPI 第2条第1項 / GDPR Art.4(1)). A
  public map asserts no lawful basis to publish it, so this is HARD — no
  advisor confidence and no human approver can override it. A residence
  with no named person (an address alone, e.g. a delivery landmark) is
  not caught here; naming the occupant is what makes it personal data."
  [{:keys [op]} proposal]
  (when (= op :poi/publish)
    (let [{:keys [residential? subject-name]} (:value proposal)]
      (when (and residential? (not (str/blank? (str subject-name))))
        [{:rule :residential-privacy-gate
          :detail (str "居住地 POI に個人名が結び付いている: " subject-name)}]))))

(defn- sensitive-subject?
  "True when the op's target listing (existing or newly-proposed) is
  flagged as concerning a real named individual/company under allegation."
  [{:keys [op subject]} proposal st]
  (case op
    :listing/publish (boolean (get-in proposal [:value :allegation-subject?]))
    :placement/feature (boolean (:allegation-subject? (store/listing st (get-in proposal [:value :listing-id]))))
    ;; POI ops carry no allegation flag — a place is not accused of
    ;; anything. Their always-hard concern is the residential-privacy
    ;; gate above. Stated explicitly so the default listing lookup below
    ;; can never coincidentally match a POI id.
    (:poi/publish :poi/search) false
    (boolean (:allegation-subject? (store/listing st subject)))))

(defn check
  "Censors a PortalCurator-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :sensitive? bool
    :hard? bool :takedown? bool}.

   - :hard?       — at least one HARD violation (rbac/source-provenance-
                    gate/license-scope-gate/disclosure-gate/licensed-
                    disclosure/geo-bounds-gate/entity-verification-gate/
                    residential-privacy-gate). Forces HOLD; a human cannot
                    override.
   - :escalate?   — soft: low confidence, sensitive-subject listing, OR a
                    takedown request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-serve."
  [request context proposal st]
  (let [hard        (into []
                          (concat (rbac-violations request context)
                                  (source-provenance-violations request proposal st)
                                  (license-scope-violations request proposal)
                                  (disclosure-violations request proposal)
                                  (licensed-disclosure-violations request context proposal st)
                                  (geo-bounds-violations request context proposal)
                                  (entity-verification-violations request proposal st)
                                  (residential-privacy-violations request proposal)))
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
