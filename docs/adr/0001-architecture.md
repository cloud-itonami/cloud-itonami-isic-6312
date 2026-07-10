# ADR-0001: cloud-itonami-isic-6312 — PortalCurator-LLM を封じ込めた知能ノードとするWebポータル・コンテンツ集約アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-8291`(収集・保持・
  契約者限定開示パターンの原型)、`cloud-itonami-isic-7820`(default-phase
  を実装当初から保守的値にする設計の踏襲元)
- 文脈: com-junkawasaki/root superproject ADR(本 ADR の対)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから、ISIC Rev.4
6312「Web portals」を選定した(3桁の親コード「Web search portals and
other information service activities」の子として位置づけられる、既存
6311「market data」の姉妹スロット)。

third-party ソースの要約・featured 配置案・広告主向け開示列の提案には LLM
が有効だが、**LLM に掲載・配置・開示・削除確定を直接行わせるのは危険**で
ある(出典なきリスティングの断定=汚染コンテンツ伝播、fair-use 範囲を超えた
全文転載=著作権侵害、開示ラベル無しスポンサード配置=FTC違反、告発対象記事
の自動配置=名誉毀損リスク)。したがって設計課題は「LLM でポータルを回す」
ことではなく、**「LLM を信頼境界の内側に封じ込め、著作権スコープ・開示
義務・ライセンス・機微性・人間レビューの層をどう被せるか」**である。

## 決定

### 1. PortalCurator-LLM は最下層の1ノードに封じ込め、直接掲載/配置/開示/削除確定させない

OperationActor 内で PortalCurator-LLM は *proposal*(リスティング案・配置
案・開示列案・削除解決案 ＋ 出典/根拠トレース)のみを返す**助言者**として
扱う。出力は必ず独立した `PortalGovernor` を通してから台帳に commit する。
**単一の不変条件**:

> **PortalCurator-LLM は、PortalGovernor が拒否するリスティングの掲載・
> 配置・開示・削除確定を決して行わない。**

### 2. PortalGovernor は8チェック(HARD5 + SOFT3)

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | source-provenance-gate | HARD | 出典クラスが許可リストに無ければ拒否。`:licensed-syndication` はアクティブな `content-license` を要求 |
| 3 | **license-scope-gate**(新規) | HARD | `:fair-use-excerpt` 出典のリスティングは抜粋長が保守的上限(400文字)を超えたら拒否。17 U.S.C. §107 根拠、ブライトラインでないことを明記した operator 調整可能な安全マージン |
| 4 | **disclosure-gate**(新規) | HARD | スポンサード配置に開示ラベルが無ければ拒否(FTC native-advertising、16 CFR Part 255) |
| 5 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | sensitive-subject gate | SOFT | 告発対象のリスティング配置 → 必ず人間承認 |
| 8 | takedown-request | SOFT(無条件) | 削除/訂正申立ては確信度に関わらず常に人間レビュー |

**意図的に無い項目**: 与信/決済関連チェックは存在しない — この actor は
コンテンツの集約・配置・開示のみを行い、注文執行・決済処理を一切含まない。

### 3. Phase 0→3、default-phase = 1(実装当初から保守的)

`cloud-itonami-isic-7820` で確立された規律に倣い、`default-phase` は
実装当初から `1`(assisted、auto-commit 無し)。`:phase` を省略した呼び出し
元が最大自律性を得る fail-open を、過去に遡って直す必要のない形で最初から
回避した。`:takedown/request` はどの phase の `:auto` にも入らない構造的
恒久ゲート。

### 4. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/portal/facts.cljc`)は実在する3つの自由・公式法的根拠
(US federal public domain: 17 U.S.C. §105、CC BY 4.0、fair-use excerpt:
17 U.S.C. §107)+ 1つの構造的クラス `:licensed-syndication`(operator が
自前のライセンス契約を `content-license` レコードとして登録して初めて
取込可能)。`facts/coverage` が常に正直に現状を報告する。

### 5. Robotics premise: false

物理的な配送・実物資産の移動を伴わない、コンテンツの集約・配置・開示のみの
デジタルサービスであり、actor の境界の外に物理的な作動は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 6312 スロットが実装へ昇格。
- (+) license-scope-gate・disclosure-gate という、他の cloud-itonami
  actor に存在しないコンテンツ集約業固有の HARD チェックを新設した。
- (+) `clojure -M:dev:test`: 35 tests / 137 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(8シナリオ全て正しく発火)。
- (-) R0 の自由法的根拠は3種のみ。ほとんどの商用シンジケーション契約は
  operator の content-license 登録が必須で、この actor 単体では取込できな
  い。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。

## 代替案と不採用理由

- **LLM に掲載・配置権限を直接付与(エージェント自律)**: 速いが、出典なき
  断定・著作権範囲超過・開示義務違反・名誉毀損リスクを構造的に防げない。
  単一不変条件(決定1)に反する。
- **license-scope-gate を SOFT にとどめる**: fair-use の範囲逸脱は確信度
  と無関係に起きるため、SOFT では低確信フィルタをすり抜ける高確信の全文
  転載を止められない。HARD が必須と判断した。

## References

- `README.md` + `docs/business-model.md`(本リポジトリ)
- `cloud-itonami-isic-6311`(直接の手本、フリート標準パターン)
- `kotoba-lang/industry` registry.edn(id "6312" エントリ)
