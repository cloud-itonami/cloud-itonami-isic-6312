(ns portal.llm
  "PortalCurator-LLM client — the *contained intelligence node*.

  It drafts aggregated listings from third-party sources, proposes
  placement/featuring decisions, proposes advertiser report column sets,
  and drafts takedown-dispute resolutions. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields/source it cited), never a committed or published record. Every
  output is censored downstream by `portal.policy` (the PortalGovernor)
  before anything touches the SSoT or is disclosed.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-provenance gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str :license-id str?}|nil ; SCANNED by source-provenance
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the record patch, for publish/feature/takedown
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]
            [portal.store :as store]))

(defn- propose-publish
  "Listing draft from a third-party source. `:unsourced?` injects the
  failure mode we must defend against: a listing arriving with no source
  citation at all — the PortalGovernor's source-provenance-gate must
  reject this outright, regardless of how confident the LLM is."
  [_db {:keys [subject title snippet source-id category allegation-subject? source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "listing publish: " title)
     :rationale "出典引用済み third-party ソースの正規化・要約のみ。"
     :cites     [:title :snippet :source-id :category]
     :source    src
     :effect    :listing-upsert
     :value     {:id subject :title title :snippet snippet :source-id source-id
                 :category category :status :live :as-of "2026-07-10T12:00:00Z"
                 :allegation-subject? (boolean allegation-subject?)}
     ;; deliberately HIGH confidence even when unsourced? — proves the hard
     ;; source-provenance gate does not care about confidence at all.
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-feature
  "Placement/featuring draft. `:no-disclosure?` injects the failure mode
  this actor exists to catch: an LLM proposing a sponsored placement with
  no disclosure label (a native-advertising violation) — the
  PortalGovernor's disclosure-gate must reject this regardless of
  confidence."
  [_db {:keys [subject listing-id sponsored? disclosure-label no-disclosure?]}]
  (let [label (if no-disclosure? nil disclosure-label)]
    {:summary   (str "placement feature: " listing-id " → " subject)
     :rationale (if no-disclosure?
                  "スポンサード配置だが開示ラベルの提案を省略(欠落シナリオ)。"
                  (str "開示ラベル: " label))
     :cites     [:listing-id :sponsored?]
     :source    nil
     :effect    :placement-upsert
     :value     {:slot-id subject :listing-id listing-id :sponsored? (boolean sponsored?)
                 :disclosure-label label :as-of "2026-07-10T12:00:00Z"}
     ;; deliberately HIGH confidence even when no-disclosure? — proves the
     ;; hard disclosure gate does not care about confidence at all.
     :confidence 0.9}))

(defn- propose-report
  "Advertiser report column-set proposal. `:greedy?` injects over-
  disclosure (pulls snippet/allegation-subject columns beyond a
  basic-tier contract) — the PortalGovernor's licensed-disclosure gate
  must reject the excess columns."
  [_db {:keys [subject greedy?]}]
  (let [base [:listing-id :title :category :status :as-of]
        greedy-extra [:source-id :snippet :allegation-subject?]]
    {:summary   (str "開示列提案: " subject)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-poi-publish
  "POI (map pin) draft. Two injectable failure modes specific to the map
  slice, both of which the advisor is structurally incapable of catching:
  `:drop-coord?` simulates a geocoder returning nothing (the pin would
  land at a meaningless coordinate), and an unresolvable/lapsed `:lei` or
  a residence tied to a named person simply comes through as data — the
  governor's entity-verification and residential-privacy gates reject
  those independently of anything the advisor believes."
  [_db {:keys [subject name lat lng category isic-code lei source-id residential?
               subject-name source unsourced? drop-coord?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "POI publish: " name)
     :rationale "地物情報の正規化のみ。法人実体の実在性・居住地の可否は判断していない(governor が判定)。"
     :cites     [:name :lat :lng :category :isic-code :lei :source-id]
     :source    src
     :effect    :poi-upsert
     :value     {:id subject :name name
                 :lat (when-not drop-coord? lat) :lng (when-not drop-coord? lng)
                 :category category :isic-code isic-code :lei lei
                 :source-id source-id :status :live
                 :residential? (boolean residential?) :subject-name subject-name
                 :as-of "2026-07-10T12:00:00Z"}
     ;; deliberately HIGH confidence in every failure mode — proves the
     ;; three map-slice HARD gates do not care about confidence at all.
     :confidence 0.95}))

(defn- propose-poi-search
  "Governed geo disclosure draft: a radius search around a center point,
  with a proposed column set. `:greedy?` injects over-disclosure (pulls
  the fleet join keys and the audit-only columns beyond a basic-tier
  contract) — the licensed-disclosure gate must reject the excess."
  [_db {:keys [center-lat center-lng radius-km greedy?]}]
  (let [base [:poi-id :name :lat :lng :category :status :as-of :distance-km]
        greedy-extra [:lei :isic-code :source-id :subject-name]]
    {:summary   (str "POI検索: 半径 " radius-km "km")
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :poi-disclosure-serve
     :value     {:lat center-lat :lng center-lng :radius-km radius-km}
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-takedown
  "Takedown/dispute resolution draft. This NEVER auto-applies —
  `portal.policy` and `portal.phase` both structurally force every
  `:takedown/request` to human review, independent of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "listing の " disputed-field " について削除/訂正申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :correction-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :listing/publish     (propose-publish db request)
    :placement/feature   (propose-feature db request)
    :report/query        (propose-report db request)
    :poi/publish         (propose-poi-publish db request)
    :poi/search          (propose-poi-search db request)
    :takedown/request    (propose-takedown db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a
;; real LLM in production. Either way its output is a PROPOSAL the
;; PortalGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはWebポータルのコンテンツ集約・配置アドバイザーです。"
       "与えられた事実のみに基づき、提案を1つだけ EDN マップで返します。"
       "説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref .. :license-id? ..}か nil) "
       ":effect(:listing-upsert|:placement-upsert|:poi-upsert|:disclosure-serve"
       "|:poi-disclosure-serve|:correction-apply) "
       ":value(該当マップ) :confidence(0..1)。\n"
       "重要: 出典(:source)を伴わないリスティング・POI は絶対に提案してはいけません。"
       "スポンサード配置には必ず開示ラベルを提案してください。抜粋の著作権範囲判定・"
       "告発対象の機微性判断はあなたの責務ではありません(governor が判定します)。\n"
       "地図(POI)について: 座標は分かる場合のみ入れ、推測で埋めないでください。"
       "法人実体(:lei)の実在性・登録状態、居住地を公開してよいかの判断も"
       "あなたの責務ではありません(governor が独立に検証します)。"))

(defn- facts-for [st {:keys [op subject listing-id lei]}]
  (case op
    :placement/feature {:listing (store/listing st listing-id)}
    :report/query      {:listing (store/listing st subject)}
    ;; The advisor is shown the registry record but is NOT asked to judge
    ;; it — `entity-verification-gate` resolves it independently.
    :poi/publish       {:poi (store/poi st subject)
                        :lei-entity (when lei (store/lei-entity st lei))}
    :poi/search        {:poi-count (count (store/all-pois st))}
    {:listing (store/listing st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the PortalGovernor escalates/holds —
  an LLM hiccup can never auto-commit or auto-publish."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba),
  or `model/mock-model` for offline tests. `gen-opts` is forwarded to
  -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :portalllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
