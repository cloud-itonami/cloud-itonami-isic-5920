# cloud-itonami-isic-5920

**Sound Recording and Music Publishing Activities** — ISIC Rev.4 class 5920.

A coordination-only actor for recording-studio and music-publishing back-office operations, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-production-record, schedule-production-operation, coordinate-release, flag-rights-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Catalog entry verified** — target catalog entry (recording/work + its artist-contract record) must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — this actor NEVER directly finalizes a rights-licensing grant or a royalty-payment determination. These are permanently, structurally blocked, regardless of confidence, op, or human approval — see CRITICAL scope exclusions below.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: production-record logging only (approval-gated)
  - Phase 2: + schedule-production-operation, coordinate-release (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (rights concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL — scope exclusions

This actor is a back-office **operations coordination** actor only. It is **NOT** a
rights-licensing authority and **NOT** a royalty-payment authority. It never
performs or authorizes:

- Finalizing a rights-licensing grant — a decision to grant, deny, or execute a
  licensing agreement for a recording or composition is always either a hard,
  permanent block (if the advisor attempts to finalize it) or handled entirely
  outside this actor by the real rights authority (label/publisher legal, rights
  holder, or their designated licensing process). This actor may only *log an
  observation* via `flag-rights-concern`, which always escalates to a human and can
  never auto-commit. Music publishing has a direct rights/royalty-licensing
  dimension — the closed op allowlist deliberately contains no op that itself
  finalizes such a decision.
- Finalizing a royalty-payment determination — a decision to authorize, execute, or
  issue a specific royalty payment, or to set a final royalty split/amount, is
  always a hard, permanent block.

`flag-rights-concern` is the only op through which this actor may ever touch rights/
royalty territory, and it **always** requires human sign-off — it is structurally
absent from every rollout phase's `:auto` set, including phase 3 (two independent
layers agree on this: `musicops.governor/always-escalate-ops` and
`musicops.phase/phases`).

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/musicops/governor_test.clj` — unit tests of governor hard checks and scope exclusion (including a dedicated regression test that the default mock advisor's own proposals never self-trip scope-exclusion)
- `test/musicops/advisor_test.clj` — advisor proposal shape and consistency
- `test/musicops/phase_test.clj` — rollout phase logic
- `test/musicops/governor_contract_test.clj` — full graph integration, audit trail
- `test/musicops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `musicops.store` — SSoT (MemStore, String-keyed catalog directory, append-only ledger)
- `musicops.advisor` — contained intelligence node (mock + real-LLM seam)
- `musicops.governor` — independent compliance layer
- `musicops.phase` — staged rollout (0→3)
- `musicops.operation` — langgraph-clj StateGraph
- `musicops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-facing/personal-services)
fleet. See ADR-2607121000, ADR-2607152500, and the paired
`90-docs/adr/*-cloud-itonami-isic-5920-sound-recording-music-publishing-coverage.md`/`.edn`
ADR in the `com-junkawasaki/root` superproject for design decisions.
