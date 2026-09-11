# Contributing to cloud-itonami-isic-5920

Contributions should preserve the actor's scope: back-office recording-studio and
music-publishing operations coordination only, with CRITICAL exclusions of any op
that directly finalizes a rights-licensing grant or a royalty-payment determination
(see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: kbb -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize a rights-licensing grant (a decision to grant, deny, or execute a
  licensing agreement for a recording or composition). This is always either a
  hard, permanent block or an always-escalate op (`flag-rights-concern`), never
  auto-commit-eligible at any phase.
- Finalize a royalty-payment determination (a decision to authorize, execute, or
  issue a specific royalty payment, or to set a final royalty split/amount).
