# Contributing

`cloud-itonami-isic-6312` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real listing content about real named individuals/
  companies, real advertiser contract documents, or real content-license
  credentials.
- Keep production publishing and disclosures behind PortalGovernor.
- Treat every new content source or placement type as high-risk: add
  tests for source-provenance-gate, license-scope-gate, disclosure-gate,
  licensed-disclosure, confidence floor and audit logging.
- Never fabricate a source-catalog entry to expand apparent free-source
  coverage — a new syndication partner is a `content-license` record with
  a real operator license, not a catalog entry.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
