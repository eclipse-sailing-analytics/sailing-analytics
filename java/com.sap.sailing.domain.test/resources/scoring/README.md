# Offline scoring regression fixtures (#6218)

Run `com.sap.sailing.domain.test.ProductionScoringReplayTest` and
`com.sap.sailing.domain.test.ScoringSchemeInteractionTest` as JUnit tests in
`com.sap.sailing.domain.test`, with `-ea` in the launch configuration's VM
arguments. Both suites check that Java assertions in the domain model are
enabled. When using Eclipse MCP, pass a saved launch configuration with
`-ea` as `launcherName`; generated launches may omit it. Tests require no
network or credentials.

## Scope and provenance

54 JSON files represent 32 top-level boards plus 22 child boards: 1,127
competitor rows and 16,562 cells. The 26 production-represented scoring types
are complemented by synthetic cases for `HIGH_POINT_WINNER_GETS_EIGHT` and
`HIGH_POINT_BY_WINS_TIES_LASTLY_BROKEN_BY_OTHER_LEADERBOARD`, covering all 28
current types. This is behavioral regression coverage, not proof of every
rule interaction or an endorsement of every historical configuration.

Each fixture retains result/configuration URLs, capture times and hashes,
and the originally observed scheme. Full raw captures are preserved under
`/home/uhl/data/SAP/sailing/research/bug6218/6218-production-audit/`.
To regenerate from that archive, run from the repository root:

```sh
python3 java/com.sap.sailing.domain.test/resources/scoring/prepare_fixtures.py \
  /home/uhl/data/SAP/sailing/research/bug6218/6218-production-audit
```

The extractor is offline. Expected values come from captured results;
`expected*` fields are assertions only. The loader imports unweighted cell
corrections and penalty labels, constructs real regatta or flexible leaderboards and race-log
registrations, and lets Java recompute totals, discards and rankings.
Actual child leaderboards feed overall columns; only explicitly corrected
overall cells are overridden. Shared competitor identities are retained.
Parent suppression is inferred from child IDs missing from the captured
parent field, as labelled in the fixture. Every child and parent is checked
with both forward and reverse competitor insertion order.

Raw finish-rank conversion, penalties, race-log overrides, redress and
time-dependent scores are exercised separately by the interaction tests
and the existing scoring suites. `uncorrectedTotalPoints` is not imported.

## Complete flexible leaderboards

Three fresh production captures reconstruct actual `FlexibleLeaderboardImpl`
instances, preserving column order, Medal flags, factors and the default fleet:

| Fixture | Captured scheme | Competitors × columns | Scoring features |
|---|---|---:|---|
| `flexible-qingdao-2011` | `HIGH_POINT` | 9 × 29 | No discards; R29 Medal factor 2; corrected DNF |
| `flexible-kiel-2012-470w` | `LOW_POINT` | 12 × 7 | Discard threshold 5; M Medal factor 2; penalty labels |
| `flexible-505-worlds-2011` | `LOW_POINT` | 84 × 7 | Discard threshold 5; ordinary-race ties; DNF/DNS/DNC |

All 933 cells have numeric scores and expected net scores. Each replay checks
computed totals, discards, net cells and whole-field ranking in both competitor
insertion directions. Column configuration and row completeness are also asserted.
Empty exported fleet membership remains empty (two Kiel M cells and eleven 505
cells); numeric corrections still apply. The captured Kiel Medal values are
retained exactly, including fractional unweighted corrections implied by factor 2.

Results and `SailingService.getLeaderboard` replies were captured on 2026-11-12
under `flexible-fixtures-20261112/` in the audit archive, with request/time/SHA-256
provenance. Fresh GWT decoding confirmed the earlier metadata. The fourth candidate,
Kiel 2012 2.4mR, has 350 of 360 numeric cells: Cermak is entirely unscored. Its
capture is retained; the three hole-free boards above were selected for these tests.

## Important interpretations

- SCL Porto Cervo 2021 and Vilamoura 2022 use the NoR-compliant first-to-two
  scheme confirmed by the user's database corrections. Their captured old
  schemes remain in provenance. Q's flag is retained; direct Qualification
  ties and A8 including Q remain distinct, unresolved rule interpretations.
- Singapore 2015's Aberdeen Challenge Qualification has discard threshold
  `[1]`, independently captured through `SailingService.getRegattaByName`.
  The REST configuration omits this setting. Q1 is therefore discarded.
- KW2014's three official 66th places remain a sporting tie. Full replay
  permits their existing sequential display ranks 66–68; a separate test
  verifies A8.1/A8.2 equality and retains the official result provenance.
- 49erFX Europeans 2017 SI 20.1.3/20.2.3 supply local discard thresholds;
  SI 20.4.1, page 6, specifies Fleet-Racing-only A8:
  <https://www.manage2sail.com/en-US/Home/DownloadDocument/91895f73-ec37-4e9d-8fc5-6c3b63d35248>.
  ARG19's `1,4,7,10` beats ESP178jun's `1,4,8,14`. The legacy Java
  all-series path also favors ARG, but inversely scales already-unweighted
  scores (Q5: displayed 3, correction 6, A8 input 12). The focused test
  distinguishes this mechanism from the SI and labels weighted-input
  reversal as a counterfactual.
- ESS2017's eight-point override still uses a literal twelve-point base
  for penalties. The interaction test labels this legacy characterization,
  not NoR compliance.
- `HIGH_POINT_MATCH_RACING` retains its Appendix C11.1 TODO. The graph-based
  tied-cohort scheme is separately named `HIGH_POINT_FIRST_GETS_ONE`.

Full scheme mapping, remaining interaction/rules-review gaps and executed
suite results are maintained in the durable research `SCORING_COVERAGE.md`
and `CURRENT_STATE.md`.
