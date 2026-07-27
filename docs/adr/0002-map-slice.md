# ADR-0002: the map slice — governed POIs on the existing portal actor

**Status**: accepted
**Date**: 2026-07-27
**Upstream**: com-junkawasaki/root ADR-2607276000 (placement decision)

## Context

An AMap-class map product is, structurally, a web portal with
coordinates: it aggregates third-party places, ranks them, and monetizes
sponsored placement and referral. That is the business this actor
already governs (ADR-0001) — the PortalGovernor's source-provenance,
license-scope and native-advertising disclosure checks apply to a map
pin exactly as they apply to an article listing.

The upstream ADR therefore placed the map here rather than in a new
vertical (which would duplicate this actor's revenue structure) or in
`cloud-itonami-isic-7110` (already a distinct architectural/engineering
practice actor).

## Decision

Add a **map slice** to this actor: a `poi` entity, a `lei-entity`
registry record, two ops (`:poi/publish`, `:poi/search`), one new RBAC
role (`:geo-editor`), and three new HARD governor gates.

### What is genuinely new, and why it is a gate rather than a prompt

Publishing a pin asserts two things publishing an article does not.

1. **That a business exists at a place.** A fabricated business listing
   is the defining abuse of every consumer map product, and a deceptive
   representation under FTC Act §5. The structural answer is an
   identifier that resolves in a real registry: ISO 17442's Legal Entity
   Identifier, which carries a registration status. Only `:issued`
   counts — a lapsed registration is exactly the stale case the gate
   exists to catch. `entity-verification-gate` also rejects an ISIC code
   that **contradicts** the resolved record, because a silent mis-filing
   corrupts every downstream join on `:company/lei`.

2. **That a place is public.** A private residence tied to a named
   natural person is personal data under 個人情報保護法 第2条第1項 and
   GDPR Art.4(1). A public map asserts no lawful basis to publish it.
   `residential-privacy-gate` is HARD with no approver override — an
   operator cannot consent on the resident's behalf. An address with no
   named occupant is not caught; naming the occupant is what makes it
   personal data.

A third gate, `geo-bounds-gate`, is structural rather than legal: a pin
with an invalid/NaN/missing coordinate, or one outside the operator's
declared service area, is unverifiable — the operator cannot confirm a
place they cannot reach. This is the geo analog of the excerpt-length
cap: a conservative proxy, and labelled as one.

The advisor proposes every one of these failure modes at **0.95
confidence** in the test suite and the demo. All three gates are HARD
and consult confidence nowhere.

### Phase placement

`:poi/publish` enters at **phase 2**, one phase later than
`:listing/publish`. The entity-verification gate can only do its job
once the operator has actually seeded a registry for it to resolve
against; an operator with no registry should not be publishing pins at
phase 1 merely because they can publish articles.

### Disclosure tiers

`:poi/search` reuses the licensed-disclosure gate with its own column
table. `:lei` and `:isic-code` — the fleet join keys — sit at
`:tier/analytics`; a basic-tier ad buyer gets a map, not a resolvable
entity graph. `:subject-name` is `:tier/audit` only: it exists so a
trust & safety review can see *why* a residential POI was rejected, and
must never reach an advertiser.

## What this slice does NOT do

- **No renderer.** Projection, tile URLs and the Web Mercator latitude
  bound come from `kotoba-lang/map` through `portal.geo` and
  `portal.map-bridge`. This repo contains no drawing code, no tile
  client, and no second projection implementation. The sample console
  plots pins as inline SVG from computed coordinates and fetches no
  third-party tiles.
- **No ISIC verification.** A POI's ISIC code is operator-asserted, not
  registry-issued. It is only cross-checked for contradiction against a
  resolved LEI record. `portal.facts/poi-coverage` says so in the
  coverage note, and a test asserts that the note names it.
- **No China-specific behavior.** GCJ-02 coordinate offset, 測絵資質 and
  ICP 備案 are not implemented and not needed here; coordinates stay
  WGS84.
- **No money movement.** Unchanged from ADR-0001 — there is still no
  schema field for order fulfillment or payment processing.

## Consequences

- The governor grows from 8 checks to 11 (8 HARD, 3 soft/escalate).
- `portal.store` grows two entities; both backends (MemStore and the
  langchain.db DatomicStore) implement them and are covered by the same
  parity contract.
- The operator console gains a map section, a refused-pins section read
  back out of the run's own ledger, and a tier/basic search result —
  all generated at build time from a real actor run, byte-identical
  across reruns.
- Test suite: 35 tests / 141 assertions → 71 / 316.
