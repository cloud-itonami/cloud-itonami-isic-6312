# Open Business Blueprint: cloud-itonami-isic-6312

This repository publishes an OSS business model for operating a web
portal (Yahoo!-portal/AOL/MSN-portal class) on itonami.cloud, with a
collect-curate-disclose operating model: aggregate third-party content
and links, hold curation/placement decisions internally, disclose
reports only to licensed advertiser contracts.

## Classification

- Repository name: `cloud-itonami-isic-6312`
- Primary classification: ISIC Rev.4 6312
- Activity: operating a web portal — aggregating and curating third-party
  content and links, monetizing via sponsored placement/referral
- Served domain: listing curation, placement/featuring, advertiser
  reporting, takedown/dispute handling

The ISIC code describes the business activity of operating a web portal.
This actor is the sixth `:spec → real repo` promotion in
`kotoba-lang/industry`'s registry (after `cloud-itonami-M6910`'s 6910,
`cloud-itonami-isic-8291`'s 8291, `cloud-itonami-isic-4690`'s 4690,
`cloud-itonami-isic-4610`'s 4610, and `cloud-itonami-isic-6311`'s 6311).

## Customer

Primary customers (contracted, licensed access only — never public/
anonymous, for the *report* surface; the portal's front-end content
itself may of course be public):

- advertisers/ad-ops teams buying sponsored placement
- syndication partners supplying licensed content feeds
- other `cloud-itonami-{ISIC}` blueprint operators who need curated
  content/placement reporting as a licensed capability

## Problem

Portal operators (legacy Yahoo!/AOL/MSN-style aggregators, and their
modern successors) have historically blurred the line between organic
and sponsored content, and have no structural guarantee against
publishing unsourced or copyright-infringing aggregated snippets. FTC
native-advertising enforcement and copyright litigation (excerpt vs
full-text reproduction) are real, recurring risks for this business
model.

## Offer

Operators provide an OSS actor for web-portal content curation:

- aggregated listings from real, source-cited third-party content
  (public domain, CC BY, fair-use excerpt, or licensed syndication)
- placement/featuring with mandatory sponsored-content disclosure
- governed, tier-scoped advertiser reporting (never a public/anonymous
  query surface)
- a takedown/dispute channel, always human-reviewed
- immutable audit ledger of every publish/feature/disclosure event

The core promise: PortalCurator-LLM can draft listing summaries and
placement proposals, but it cannot publish, feature, or disclose unless
the independent PortalGovernor allows it.

## Revenue

Operators can sell:

- sponsored placement slots (disclosed, per the disclosure-gate)
- tiered advertiser reporting: `:tier/basic` (listing status) →
  `:tier/analytics` (+ source attribution) → `:tier/audit` (+ full
  snippet/sensitivity flags)
- wholesale API access to other `cloud-itonami-{ISIC}` blueprint
  operators
- managed hosting: monthly subscription per tenant
- syndication integration: onboarding a real licensed content partner
- compliance package: audit export, takedown-handling SLA, security
  review

| Package | Customer | Price shape |
|---|---|---|
| Basic reporting | small advertiser | per-query or low monthly tier |
| Analytics tier | ad-ops team | monthly platform fee |
| Audit tier | compliance/legal team | monthly fee + usage |
| Fleet wholesale | other cloud-itonami operators | API metering |

## Unit Economics

Track these numbers for every operator:

- source-integration hours per new syndication partner
- monthly infrastructure cost
- LLM cost per operation (publish / feature / disclosure)
- takedown/dispute handling hours per tenant
- gross margin after infrastructure and support
- churn and expansion revenue per contract tier

The business should only scale after the source catalog and every
active content-license are genuinely real (never fabricated) and
governor tests catch license-scope/disclosure/licensing
misconfiguration before production use.

## Open Participation

Anyone may:

- fork the repository
- run the demo
- deploy a self-hosted instance
- submit issues and patches
- publish compatible source-catalog extensions (real, citable legal
  bases only)
- create a local operator business

itonami.cloud should require certification before listing an operator as
a trusted provider, routing customer leads, or allowing managed
disclosure under the platform brand.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

Suggested itonami.cloud metadata:

```edn
{:itonami.blueprint/id "cloud-itonami-isic-6312"
 :itonami.blueprint/name "Web Portal Content Curation & Placement Actor"
 :itonami.blueprint/isic-rev4 "6312"
 :itonami.blueprint/domain :media/portal-curation
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-6312"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real listing content about real named individuals or
  companies.
- Do not add a schema field for order-fulfillment or payment processing.
- Do not bypass the PortalGovernor for production publishing or
  disclosures.
- Do not serve a disclosure to a tenant without an active, registered
  contract.
- Do not fabricate a source-catalog entry or a content-license record to
  expand apparent coverage.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
