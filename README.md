# cloud-itonami-isic-6312

Open Business Blueprint for **ISIC Rev.4 6312**: web portals — a
portal operator that aggregates and curates third-party content and
links (the Yahoo!-portal/AOL/MSN-portal class of business, distinct from
a search engine or a single-publisher site) and monetizes via sponsored
placement/referral, published as an OSS business that any qualified
operator can fork, deploy, run, improve and sell.

Built on this workspace's [`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
and [`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291).

> **Why an actor layer at all?** A PortalCurator-LLM is great at
> summarizing third-party sources, drafting featured-placement copy, and
> proposing advertiser report column sets — but it has **no notion of
> copyright-license scope, native-advertising disclosure law, or a
> client's disclosure entitlement**. Letting it publish or feature
> directly invites an unsourced/fabricated listing, a full-text
> reproduction exceeding a fair-use excerpt's legal basis, an undisclosed
> sponsored placement (an FTC native-advertising violation), or
> over-disclosure beyond an advertiser's contract tier. This project
> seals the PortalCurator-LLM into a single node and wraps it with an
> independent **PortalGovernor**, a human **review workflow**, and an
> immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **aggregates, holds and discloses third-party content
references**. It never handles order-fulfillment or payment
processing — there is no field anywhere in this schema for those (see
`docs/adr/0001-architecture.md`). Every listing must resolve its
provenance to one of three real, citable, free legal bases (US federal
public domain, CC BY 4.0, the fair-use excerpt doctrine) or an
operator-registered `:licensed-syndication` agreement — never a bare
"the LLM summarized it".

### Map slice (POIs)

The same portal, with coordinates. A **POI** is a curated point on the
map; publishing one asserts two things an article listing does not — that
a business at that location **exists**, and that a location is a **public
place**. Both have real legal exposure and neither is something the
PortalCurator-LLM can adjudicate, so both are governor HARD gates
(`docs/adr/0002-map-slice.md`, com-junkawasaki/root ADR-2607276000):

| Gate | Rejects | Basis |
|---|---|---|
| `entity-verification-gate` | a commercial POI with no legal entity, an unresolvable or **lapsed** registration, or an ISIC code that **contradicts** the registry record | ISO 17442 (LEI) · FTC Act §5 |
| `residential-privacy-gate` | a private residence tied to a **named** natural person | 個人情報保護法 第2条第1項 · GDPR Art.4(1) |
| `geo-bounds-gate` | an invalid/NaN/missing coordinate, or one outside the operator's declared service area | structural operator control |

`:lei` is deliberately the same join key the fleet's `cloud-itonami-lei-*`
blueprints use (`:company/lei`), so a governed POI can be joined to entity,
financial and ToS facts held elsewhere without this actor duplicating any
of them. That join key is **analytics-tier**: a `:tier/basic` search
returns a map, not a resolvable entity graph.

Rendering is **consumed, not reimplemented**: projection and tile math come
from [`kotoba-lang/map`](https://github.com/kotoba-lang/map) (the pure-`.cljc`
port of the KAMI WebGPU map renderer) via `portal.geo` and
`portal.map-bridge`. This repo contains no renderer of its own, and the
sample console fetches no third-party tiles.

## Consuming this actor from another blueprint

The governed read op is `:report/query` (a listing's curation/placement
report, columns limited to your contract tier). It always runs through
the PortalGovernor's licensed-disclosure check — there is no bypass.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, PortalGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, deploy, support and sell the service |
| Trust controls | Governance, security reporting, policy tests, audit requirements |

The primary industry classification is **ISIC Rev.4 6312** because the
commercial activity is operating a web portal that aggregates and
monetizes third-party content and links.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌────────────────┐  proposal        ┌────────────────────────┐
   │ PortalCurator-LLM│ ───────────────▶│ PortalGovernor          │  (independent system)
   │ (sealed)         │  draft + source │  license-scope ·        │
   └────────────────┘   citation        │  disclosure · human     │
                                         └────────────────────────┘
                                              │
                                   commit / publish only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: PortalCurator-LLM never publishes, features,
discloses, or resolves a takedown the PortalGovernor would reject.

## Run

```bash
kbb -M:dev:test        # governor contract · store parity · phases · facts · geo · POI gates
kbb -M:dev:run         # 13-operation demo through one OperationActor (7 content + 6 map)
kbb -M:dev:render-html # regenerate docs/samples/operator-console.html from a real run
kbb -M:lint
```

## Consuming cloud-itonami-isic-6311 for market-data content

`src/portal/marketdata_bridge.cljk` is an OPTIONAL bridge (not a
compile-time dependency of `portal.*` core) that sources a listing from a
sibling [`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
(multi-asset market-data actor) governed `:disclosure/query` result — a
finance-portal "market snapshot" widget, curated exactly like any other
third-party content item. The upstream query still goes through
isic-6311's own MarketDataGovernor first; the resulting listing then
STILL goes through this actor's own PortalGovernor unchanged (source-
provenance-gate, licensed-disclosure, ...) — a market-data-sourced
listing is never special-cased past portal's governance, only ever
composed with it. If the upstream query doesn't commit, the bridge emits
`:source nil`, which portal's own governor independently HARD-rejects too
(see `src/portal/facts.cljk`'s `:cloud-itonami-market-data-feed` class and
`test-market-data/portal/marketdata_bridge_test.cljk`).

```bash
# needs cloud-itonami-isic-6311 checked out as a sibling repo
# (../../cloud-itonami/cloud-itonami-isic-6311 relative to this repo)
kbb -M:dev:test:market-data   # runs the bridge test too (main-opts order matters)
```

## Non-Negotiables

- Do not commit real listing content about real named individuals or
  companies, real advertiser contract documents, or real content-license
  credentials.
- Do not add a schema field for order-fulfillment or payment processing.
- Do not bypass the PortalGovernor for production publishing or
  disclosures.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a source-catalog entry or a content-license record.
- Do not publish a sponsored placement without an explicit disclosure
  label.
- Do not publish a POI for a business whose legal entity cannot be
  resolved in a registry, and do not weaken `lei-accepted-statuses` to
  admit lapsed registrations.
- Do not publish a residential POI tied to a named natural person, and do
  not add an approver override for that gate — it is HARD by design.
- Do not add a renderer, tile client or projection implementation to this
  repo. Consume `kotoba-lang/map`.
- Do not move `:lei` / `:isic-code` / `:subject-name` down into
  `:tier/basic` POI disclosure.

License: AGPL-3.0-or-later.
