# Web Portal Actor Design — PortalCurator-LLM as a contained intelligence node

Yahoo!ポータル/AOL/MSNポータル級のWebポータル・コンテンツ集約サービスを、
collect-curate-disclose(内部保持・契約者限定開示)の運用で、SaaS課金に
依存せず OSS の actor として自前運用するための設計。
`cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で封じ込め
た構図)を、コンテンツ集約・配置のドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか、そしてなぜスコープを絞るのか

third-party ソースの要約・featured 配置案・広告主向け開示列の提案は LLM で
加速できる。しかし LLM は次の理由で**掲載・配置・開示・削除確定の最終権限を
持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 出典なしにリスティングを「提案」で確定 | 汚染/捏造コンテンツの伝播 |
| fair-use-excerpt 出典なのに全文転載してしまう | 著作権侵害リスク |
| スポンサード配置を開示ラベル無しで公開 | FTC native-advertising 違反 |
| 告発対象の記事を高確信のまま自動配置 | 名誉毀損・誤情報の拡散 |
| 契約 tier を超えた列を開示 | 過剰開示・契約違反 |

したがって設計課題は「LLM でポータルを回す」ことではなく、**「LLM を信頼
境界の内側に封じ込め、著作権スコープ・開示義務・ライセンス・機微性・人間
レビューの層をどう被せるか」**である。

## 2. アクター・トポロジ(監督ツリー)

```
PortalSystem (root supervisor)
│
├── CurationActor ……… third-party ソースからのリスティング正規化(:listing/publish)
├── PlacementActor ……… 配置/featuring 投影(:placement/feature)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; PortalCurator-LLM 封じ込め ★
│     ├── PortalCurator-LLM (sealed)  proposal only(src/portal/llm.cljc)
│     ├── PortalGovernor              INDEPENDENT ゲート(src/portal/policy.cljc)
│     ├── Committer                   SSoT/台帳への書き込み(src/portal/store.cljc)
│     └── Recorder                     監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(機微性の高い配置・削除申立ての interrupt を受ける)
└── DisclosureActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **PortalCurator-LLM は最下層ノードで、台帳・開示経路に直接触れない。**
   出力は常に PortalGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(掲載/配置/開示
   しない)** に倒す。robotaxi の MRC(安全停止)に相当する既定。
3. **すべてが台帳に積まれる。** 「誰が・何を・どの契約/出典で掲載/配置/
   開示したか」は監査台帳への Datalog クエリ — 監査・削除申立て紛争が
   同一ファクトログから出る。

## 3. OperationActor 内部(PortalCurator-LLM ラッパー)

`src/portal/operation.cljc` の langgraph StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

チャネル: `:request :context :proposal :verdict :disposition :record :approval :audit`

- **`:context` は外部注入**(`{:actor-id .. :actor-role .. :tenant .. :phase ..}`)。
  PortalCurator-LLM はこれを持たない。
- **`:govern` は PortalCurator-LLM と別系統**(出典クラス表 + 抜粋上限 +
  開示義務 + 契約 tier 表)。LLM 提案を*拒否*して hold に substitute できる。
- **`interrupt-before #{:request-approval}`** で実際の人間レビューへ。
  レビュアーは resume 時に `{:approval {:status :approved}}` を注入する。

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`portal.store/Store` プロトコル): `MemStore`(既定)/
  `DatomicStore`(`langchain.db` = Datomic-API 互換 EAV)。両者は同一契約
  テストで等価性を保証。
- **Advisor**(`portal.llm/Advisor` プロトコル): `mock-advisor`(既定)/
  `llm-advisor`(`langchain.model` の ChatModel)。応答破損時は confidence 0
  の noop に落ち、LLM 不調が auto-commit/公開にならない。
- **Phase**(`portal.phase`、context の `:phase 0..3`、既定値は **1**):
  段階導入。read-only → assisted → supervised-auto。governor より保守的
  にしか働かない。**`:takedown/request` はどの phase の `:auto` にも
  入らない**(恒久ゲート)。

## 4. PortalGovernor(独立検閲層)

`src/portal/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate に
判定する。

```clojure
(policy/check request context proposal store)
;; => {:ok? bool :violations [..] :confidence c :escalate? bool :sensitive? bool :takedown? bool}
```

判定の優先順位(上が強い、HARD は人間承認でも上書き不可):

1. **RBAC** — `permissions` 表で `actor-role × operation` を引く。
2. **source-provenance-gate** — `:listing/publish` の `:source` が
   `portal.facts/allowed-source-classes` に無ければ HARD violation。
   `:licensed-syndication` は加えてアクティブな `content-license` を要求。
3. **license-scope-gate**(新規、web-portal 固有) — `:fair-use-excerpt`
   出典のリスティングは、抜粋長が保守的な上限(400文字)を超えたら HARD
   violation。17 U.S.C. §107 の excerpt/commentary 法理に根拠を置く
   (法的なブライトラインテストは存在しないため、これは operator 調整
   可能な保守的な構造的プロキシであると明記)。
4. **disclosure-gate**(新規、web-portal 固有) — `:placement/feature` が
   `:sponsored? true` なのに `:disclosure-label` が無ければ HARD
   violation。FTC native-advertising 開示義務(16 CFR Part 255)に根拠を
   置く。
5. **licensed-disclosure** — `:report/query` は Store 登録済みの有効な
   契約(tenant×tier)を要求し、開示列がその tier 内か。
6. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
7. **sensitive-subject gate** — 対象リスティングが実在の個人/企業への
   告発を含む → 必ず人間承認(soft)。
8. **takedown-request** — `:takedown/request` は常に escalate(soft だが
   confidence に関わらず無条件)。

## 5. SSoT と監査台帳

`src/portal/store.cljc`。dev は in-mem の EDN 事実層(本番は Datomic)。

- **entities**: `sources`(license-class別) `listings`(集約リスティング)
  `placements`(配置/featuring) `content-licenses`(取込ライセンス)
  `contracts`(advertiser licensing)。
- **commit-record!**: 操作結果を SSoT に反映(`:disclosure-serve` は SSoT
  変更なし — 台帳のみ)。
- **append-ledger!**: 全 commit/reject/開示を**不変台帳**に積む。

## 6. 開示(governed read)

`src/portal/report.cljc`。`render-listing` は PortalGovernor が承認した
列のみを出力する。列ポリシーはコードで固定される。

## 7. デモ(`clojure -M:dev:run`)

`src/portal/sim.cljc` が8操作を actor に通す(§sim.cljc docstring 参照):
公共ドメイン記事掲載 → commit、出典なしリスティング → hold、tier超過/
未契約の開示 → hold ×2、抜粋上限超過 → hold、開示ラベル無しスポンサード
配置 → hold、告発対象の配置 → 人間承認 → commit、削除/訂正申立て → 常に
人間承認 → commit。

## 8. テスト(`clojure -M:dev:test`)

`test/portal/policy_contract_test.clj` が**ガバナンス契約を実行可能**に
する。`test/portal/phase_test.clj` が段階導入と「削除申立ては恒久的に
人間専用」、そして**デフォルト phase(1)を省略した呼び出し元が最大自律性を
得ない**ことを保証する。`test/portal/facts_test.clj` が出典カタログ自体の
正直さ(捏造禁止)を保証する。

## 9. 実装と業態の対応(Yahoo!/AOL/MSN → web-portal actor)

| 実在業態の機能 | web-portal actor での実体 |
|---|---|
| コンテンツ集約 | `store` listings + `:listing/publish` |
| featured 配置/広告枠 | `store` placements + `:placement/feature` |
| ネイティブ広告開示 | disclosure-gate |
| 著作権スコープ管理 | license-scope-gate + `portal.facts` |
| シンジケーション・ライセンス管理 | `store` content-licenses + source-provenance-gate |
| 名誉毀損リスクのある記事の配信制御 | sensitive-subject gate |
| DMCA型削除/訂正申立て | `:takedown/request`(恒久 human-only) |
| アクセス権限・広告主契約 | PortalGovernor RBAC 表 + `contracts` |
| (SaaS/従来ベンダーと同型)監査台帳 | `store` append-only ledger |
