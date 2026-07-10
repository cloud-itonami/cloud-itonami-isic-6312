(ns portal.sim
  "Demo runner: push seven representative operations through one
  OperationActor and watch the PortalGovernor + approval workflow earn the
  PortalCurator-LLM the right to publish, feature, disclose or resolve a
  takedown.

    op1  公共ドメイン記事の掲載(出典あり)                    → commit
    op2  リスティングが出典なし(フィード欠落)                → source-provenance REJECT → hold
    op3  開示クエリが tier/basic 契約なのに snippet 等を要求  → licensed-disclosure REJECT → hold
    op3a 開示クエリが未契約 tenant から                       → licensed-disclosure REJECT → hold
    op4  fair-use-excerpt 出典なのに抜粋が上限超過            → license-scope-gate REJECT → hold
    op5  スポンサード配置なのに開示ラベル無し                 → disclosure-gate REJECT → hold
    op6  告発対象を含むリスティングの配置(出典・許容範囲は正常でも人間承認) → escalate → approve → commit
    op7  削除/訂正申立て(どの phase でも常に人間レビュー)     → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [portal.store :as store]
            [portal.operation :as op]
            [portal.facts :as facts]
            [portal.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a trust & safety officer 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "trustsafety-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        editor {:actor-id "ed-1" :actor-role :content-editor :phase 3}
        adops  {:actor-id "ao-1" :actor-role :ad-ops :phase 3}
        tsafe  {:actor-id "ts-1" :actor-role :trust-safety-officer :phase 3}]

    (line "── R0 出典カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (PortalCurator-LLM sealed; PortalGovernor active) ──")

    (line "\nop1  公共ドメイン記事の掲載(出典あり)")
    (run-op! actor "op1"
             {:op :listing/publish :subject "li-300" :title "デモ公共ドメイン記事"
              :snippet "米国連邦機関の公表資料からの転載(デモ、公共ドメイン)。"
              :source-id "src-gov1" :category :public-affairs :allegation-subject? false
              :source {:class :public-domain :ref "usc-17-105:demo"}}
             editor true)

    (line "\nop2  リスティング — PortalCurator-LLM が出典なしで提案(フィード欠落)")
    (run-op! actor "op2"
             {:op :listing/publish :subject "li-400" :title "デモ記事C"
              :snippet "出典不明のデモ記事。" :source-id "src-cc1" :category :lifestyle
              :allegation-subject? false
              :source {:class :cc-attribution :ref "cc-by-4:demo"} :unsourced? true}
             editor true)

    (line "\nop3  開示クエリ(tier/basic 契約なのに snippet/allegation-subject まで要求)")
    (run-op! actor "op3"
             {:op :report/query :subject "li-100" :greedy? true}
             {:actor-id "adv-1" :actor-role :advertiser-client :tenant "tenant-basic"} true)

    (line "\nop3a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op3a"
             {:op :report/query :subject "li-100"}
             {:actor-id "adv-2" :actor-role :advertiser-client :tenant "tenant-ghost"} true)

    (line "\nop4  fair-use-excerpt 出典なのに抜粋が上限(400文字)を超過")
    (run-op! actor "op4"
             {:op :listing/publish :subject "li-500" :title "デモ記事D(長文抜粋)"
              :snippet (apply str (repeat 500 "あ")) :source-id "src-news1"
              :category :news :allegation-subject? false
              :source {:class :fair-use-excerpt :ref "usc-17-107:demo"}}
             editor true)

    (line "\nop5  スポンサード配置なのに開示ラベル無し")
    (run-op! actor "op5"
             {:op :placement/feature :subject "slot-home-2" :listing-id "li-100"
              :sponsored? true :disclosure-label "Sponsored" :no-disclosure? true}
             adops true)

    (line "\nop6  告発対象を含むリスティングの配置(出典・許容範囲は正常でも人間承認)")
    (run-op! actor "op6"
             {:op :placement/feature :subject "slot-home-3" :listing-id "li-200"
              :sponsored? false :disclosure-label nil}
             adops true)

    (line "\nop7  削除/訂正申立て — 誤って掲載された内容の是正(どの phase でも常に人間レビュー)")
    (run-op! actor "op7"
             {:op :takedown/request :subject "li-100" :disputed-field :title
              :claim "デモ公共ドメイン記事(訂正済み)"}
             tsafe true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-listing db "li-100" [:listing-id :title :category :status :as-of])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの契約/出典で publish/feature/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
