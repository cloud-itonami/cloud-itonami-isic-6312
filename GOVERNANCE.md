# Governance

`cloud-itonami-isic-6312` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- PortalCurator-LLM cannot directly publish, feature, disclose or resolve
  a takedown request.
- PortalGovernor remains independent of the advisor.
- hard governor violations (source-provenance-gate, license-scope-gate,
  disclosure-gate, licensed-disclosure) cannot be overridden by human
  approval.
- a takedown/dispute request never auto-resolves, at any rollout phase.
- a sponsored placement is never published without an explicit disclosure
  label.
- every commit, hold and disclosure event is auditable.
- no schema field exists for order-fulfillment or payment processing —
  scope is structural, not a runtime filter someone could forget to call.
- real content-license credentials and real advertiser contract documents
  stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing data to an uncontracted party
- publishing an unsourced or unlicensed listing
- publishing a sponsored placement without disclosure
- misrepresenting certification status
- failing to respond to security incidents or takedown requests
- hiding material changes to customer-facing operation
