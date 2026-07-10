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
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 8-operation demo through one OperationActor
clojure -M:lint
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

License: AGPL-3.0-or-later.
