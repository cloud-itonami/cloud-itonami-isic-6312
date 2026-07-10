(ns portal.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-isic-6311`/`cloud-itonami-isic-7820`'s rollout phases:
  start narrow (read-only), widen as trust grows. Where the PortalGovernor
  answers 'is this allowed?', the phase answers 'how much autonomy does
  the actor have *yet*?'. It can only ever make the actor MORE
  conservative than the governor: it downgrades a governor-clean commit
  to approval or hold, never the reverse.

    Phase 0  read-only         — no writes at all. `:report/query` only
                                 (still governor-gated).
    Phase 1  assisted-publish  — `:listing/publish` allowed, every publish
                                 needs human approval.
    Phase 2  + feature/takedown — adds `:placement/feature` and
                                 `:takedown/request` (still approval-only).
    Phase 3  supervised auto   — governor-clean, high-confidence
                                 `:listing/publish`/`:placement/feature`
                                 may auto-commit.

  `:takedown/request` is deliberately NEVER a member of any phase's
  `:auto` set, at any phase — a rightsholder/subject dispute always
  reaches a human, independent of the PortalGovernor's own always-escalate
  check on the same op.

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted
  disposition plus a reason when the phase changed it.")

(def read-ops  #{:report/query})
(def write-ops #{:listing/publish :placement/feature :takedown/request})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:takedown/request` is intentionally
  absent from every phase's `:auto` set."
  {0 {:label "read-only"         :writes #{}
                                  :auto #{}}
   1 {:label "assisted-publish"  :writes #{:listing/publish}
                                  :auto #{}}
   2 {:label "assisted-feature"  :writes #{:listing/publish :placement/feature :takedown/request}
                                  :auto #{}}
   3 {:label "supervised-auto"   :writes #{:listing/publish :placement/feature :takedown/request}
                                  :auto #{:listing/publish :placement/feature}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (portal.operation: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase number (`(get
  phases phase (get phases default-phase))`). This is directly reachable
  by any ordinary caller that simply omits :phase — not just malformed/
  malicious input — so it must be the MOST CONSERVATIVE phase, never the
  most permissive (the same fail-open shape found and fixed this session
  in the `cloud-itonami-isic-6311`/`cloud-itonami-isic-7820`/`gftd-talent-
  actor` sibling templates: a caller who forgets :phase must never
  silently get maximum autonomy). `:takedown/request` is unaffected
  either way — never in any phase's `:auto` set."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads (`:report/query`) pass through unchanged (phase restricts write
    autonomy, not governed reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean. `:takedown/request` is never
    auto-eligible at any phase, so it always lands here once phase ≥ 2."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a PortalGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
