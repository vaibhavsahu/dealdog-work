# DealDog work trial — package map

This package is split so the submitted work is clearly separated from the material that was
supplied with the trial.

```
.
├── run.sh          -> entry point required by the contract (delegates to deliverable/run.sh)
├── outputs/        -> generated artifacts the contract asks for, at the path it names
├── deliverable/    -> MY SUBMISSION: source, tests, docs, scripts
└── provided/       -> UNMODIFIED trial package: brief, contract, fixtures, public validator
```

## Start here

| I want to... | Go to |
|---|---|
| Run everything and see pass/fail per requirement | `./deliverable/scripts/acceptance.sh` |
| Read the submission write-up | [`deliverable/README_TRIAL.md`](deliverable/README_TRIAL.md) |
| See how to validate it, and the acceptance review | [`deliverable/VALIDATION.md`](deliverable/VALIDATION.md) |
| Start the service | `./run.sh` (serves on `$PORT`, default 8080) |
| Read the original brief | [`provided/CANDIDATE_INSTRUCTIONS.md`](provided/CANDIDATE_INSTRUCTIONS.md) |

## deliverable/ — the submission

| Path | What it is |
|---|---|
| `pom.xml`, `src/main/java/**` | Java 17 / Spring Boot service (10 classes) |
| `src/test/java/**` | 22 automated tests, incl. the 7 executable invariants |
| `run.sh` | builds if needed, serves on `$PORT` in the foreground |
| `README_TRIAL.md` | required submission write-up |
| `VALIDATION.md` | how to validate + requirement-by-requirement acceptance review |
| `docs/01..07` | per-stage design docs, HLD, LLD, curl cookbook |
| `scripts/acceptance.sh` | one-command acceptance run (8 steps, PASS/FAIL, non-zero on failure) |
| `scripts/check_artifacts.py` | 35 executable contract checks (`C1`–`C35`) |
| `scripts/load_corpus.sh` | ingest the corpus into a running service |
| `scripts/generate_outputs.sh` | regenerate `outputs/` from a clean throwaway instance |
| `scripts/verify_replay.sh` | positive-evidence replay/idempotency check |
| `scripts/reference_pipeline.py` | development harness used to validate the algorithm offline (see `README_TRIAL.md` §13) |

## provided/ — supplied with the trial, unmodified

`README.md`, `CANDIDATE_INSTRUCTIONS.md`, `SUBMISSION_CONTRACT.md`, `TASK.md`, `VERSION`,
`data/` (fixtures + `IDENTITY_POLICY.json`), `validation/` (the public validator and its cases).

Nothing here was edited. The service reads `provided/data/` and the scripts invoke
`provided/validation/validate_outputs.py`; both locations are resolved automatically, or can be
overridden with `DEALDOG_PROVIDED_DIR`.

## outputs/ — generated artifacts

`normalized_listings.json`, `catalog.json`, `resolution_decisions.json`, produced by processing
the supplied initial data plus the three incremental phases in order. Kept at the package root
because `SUBMISSION_CONTRACT.md` names `outputs/` explicitly.
