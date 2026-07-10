# Security Policy

This project handles third-party content aggregation, advertiser
licensing and copyright/defamation-risk data. Treat vulnerabilities as
potentially high impact even when the demo data is synthetic — an
unauthorized bypass of the PortalGovernor could publish unsourced,
copyright-infringing, or defamatory content, or an undisclosed sponsored
placement.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or content-license-key exposure
- PortalGovernor bypass (source-provenance-gate, license-scope-gate,
  disclosure-gate, licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond an advertiser contract's tier
- tenant isolation failures
- publication of content through an undocumented, unsourced path
- publication of a sponsored placement without a disclosure label

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on content data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets and content-license keys outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for content editors, ad-ops and service accounts.
- Alert on any source-provenance-gate or disclosure-gate HOLD spike — it
  may indicate a compromised or malfunctioning upstream feed integration.
