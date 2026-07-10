# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-6312`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6312
cd cloud-itonami-isic-6312
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious listings. Production listings
must stay outside the repository and be injected through a store
adapter, and every listing must carry a real, verifiable source
citation.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one organization owns infrastructure and content |
| Managed tenant | an operator hosts for an advertiser |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo listings with real, source-cited content (extend
  `portal.facts/catalog` honestly for free legal bases — never fabricate
  one — and register real `content-license` records for licensed
  syndication partners)
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret
  manager
- define advertiser contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the takedown/dispute-handling SLA
- get written legal review for the jurisdictions you serve (copyright
  fair-use scope, FTC/consumer-protection native-advertising rules, and
  defamation law all vary by jurisdiction)

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real, citable free legal basis (e.g. US federal public
   domain content), or one real licensed syndication partner
2. prove governed, tier-scoped advertiser reporting end to end
3. run one sponsored-placement workflow in assisted mode (human-approved,
   disclosure-gate enforced)
4. export the audit ledger for review
5. convert to a metered or subscription contract

Avoid selling broad "全世界のコンテンツを自由に集約可能" before the
source/content-license catalog actually covers what a customer needs —
report coverage honestly (`portal.facts/coverage`), never oversell.

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (source → governor → disclosure)
- backup/restore evidence
- incident contact and response window
- proof that production publishing/disclosures go through PortalGovernor
- proof that real content-license credentials are not stored in Git
- proof that a takedown/dispute channel exists and is human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- lawful basis for each content source and syndication partner
- local copyright/fair-use, native-advertising-disclosure and
  defamation-law review
- secure infrastructure and tenant isolation
- honest source-catalog and content-license maintenance
- human review workflow for sensitive-subject and takedown-request
  operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does
not make an operator compliant by itself, and it does not license or
endorse reproduction of any specific publisher's content.
