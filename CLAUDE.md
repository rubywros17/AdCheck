# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

AdCheck helps consumers check the advertising claims on health-supplement product pages before buying. It is a monorepo with two independent projects:

- `backend/` — Spring Boot 4 (Java 21) analysis API
- `extension/` — Manifest V3 Chrome Extension (React + TypeScript, built with Vite)

The backend currently has **no real claims analysis**: `MockClaimAnalyzer` (a simple keyword matcher) stands in for the eventual AI/rule-based pipeline, so the full request/response flow can be validated end-to-end without medical/legal judgment logic being implemented yet.

## Commands

### Backend (`backend/`)

```powershell
cd backend
./gradlew.bat clean build          # full build + tests
./gradlew.bat bootRun               # run the API (requires DB_USERNAME, DB_PASSWORD env vars)
./gradlew.bat test                  # run all tests
./gradlew.bat test --tests "com.adcheck.analysis.service.AnalysisServiceTest"      # single test class
./gradlew.bat test --tests "*.AnalysisServiceTest.someMethodName"                  # single test method
```

- Java toolchain: 21. Build tool: Gradle wrapper (`gradlew.bat` on Windows).
- DB: PostgreSQL via Flyway migrations (`src/main/resources/db/migration/V{n}__description.sql`). Tests run against H2 with `ddl-auto: create-drop` (see `application-test.yaml`), so schema drift between Flyway migrations and JPA entities won't be caught by the test suite — verify against Postgres/Flyway when changing entities.
- `DB_URL` defaults to `jdbc:postgresql://localhost:5432/adcheck`; `DB_USERNAME`/`DB_PASSWORD` are required with no defaults.
- The analysis API is `POST /api/v1/analyses`.

### Extension (`extension/`)

```powershell
cd extension
npm install
npm run build     # runs `tsc --noEmit` then `vite build` — this is the only script (no lint/test script exists)
```

- Load `extension/dist` as an unpacked extension via `chrome://extensions` (Developer mode).
- To point at a non-default backend, set `VITE_API_BASE_URL` in `extension/.env` (copy from `.env.example`) and rebuild.
- Local fixture for manual testing: `python -m http.server 4173 --directory extension/fixtures`, then open `http://localhost:4173/product-page.html` and run AdCheck from the side panel.
- There is no test runner or linter configured for the extension yet — `tsc --noEmit` (part of `npm run build`) is the only automated check.

## Backend architecture

Code is organized by domain package under `com.adcheck.*`, not by technical layer. Most packages (`rule`, `product`, `adcase`, `reference`, `finding`) are reference/lookup data — domain entities + Spring Data repositories seeded from the CSVs in the top-level `data/` directory (health-functional-food ingredient master data, official functional claims, prior ad-violation cases, etc.), used to check extracted ad claims against officially recognized wording. `analysis` is where all the orchestration logic lives.

`auth`, `user`, `video`, and `global/security` are empty placeholder packages (directories exist, no files) — not yet implemented, ignore them until code appears there.

### Analysis request lifecycle

This is the part most likely to need cross-file understanding. Flow through `AnalysisService.analyze()`:

1. **`AnalysisResultResolver.resolve()`** builds an `AnalysisReuseKey` (normalized URL + content hash of the request + pipeline version from `AnalysisProperties`) and checks the DB (`AnalysisRepository`) for:
   - a **completed** analysis with the same key that is still fresh (`AnalysisProperties.reuseTtl`, default 7d) → returned directly as `Reused` (HTTP 200, `AnalysisResultSnapshot` deserialized from the stored `result_json` JSONB column).
   - an **active** (PENDING/PROCESSING) analysis with the same key → `InProgress` (HTTP 202, no results yet).
   - otherwise → `NewAnalysis`.
2. For a new analysis, `AnalysisLifecycleService.createPending()` inserts a row. A partial unique index (`uk_analyses_active_result_reuse`, migration V2) enforces at most one active PENDING/PROCESSING row per reuse key at the DB level — a `DataIntegrityViolationException` here means a concurrent duplicate request raced this one; `AnalysisActiveReuseConstraintDetector` distinguishes that specific constraint from other integrity violations, and the service recovers by looking up and returning the now-existing active row instead of failing.
3. `AnalysisBackgroundJob.process()` is submitted to a dedicated executor (`@Async(AnalysisAsyncConfiguration.EXECUTOR_NAME)`, not the default Spring async pool) and runs `ClaimAnalyzer` (currently `MockClaimAnalyzer`), then persists the result (completed) or failure via `AnalysisLifecycleService`. If the executor's queue is full, `TaskRejectedException` is translated into `AnalysisQueueFullException` and the pending row is marked failed rather than left stuck in PENDING.
4. The initial HTTP response for a new/in-progress analysis carries no results (`PENDING`/`PROCESSING`, empty findings) — the client is expected to poll `POST /api/v1/analyses` again with the same request to eventually get the `Reused` result once processing completes.

`AnalysisAsyncConfiguration.Async` properties (`adcheck.analysis.async.*`: core-pool-size, max-pool-size, queue-capacity, thread-name-prefix) are validated at startup via Jakarta Bean Validation (`@Min`, `@NotBlank` on `AnalysisProperties.Async`) plus a manual `core-pool-size <= max-pool-size` check in the executor's `@Bean` method — invalid config fails application startup rather than silently degrading (e.g. `queueCapacity <= 0` would otherwise make Spring silently swap in a `SynchronousQueue`).

### Result codecs

Completed results are stored as JSON in the `analyses.result_json` column and round-tripped through `AnalysisResultSnapshotMapper`/`AnalysisResultJsonCodec` — when changing `AnalysisResponse`/`FindingResponse` shapes, check whether old stored `result_json` rows still deserialize (there's no versioned migration for stored JSON payloads).

### Error handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) is the single place mapping exceptions to `ApiErrorResponse`. Bean validation failures on `CreateAnalysisRequest` become `VALIDATION_ERROR` with per-field messages; anything unhandled becomes a generic `INTERNAL_SERVER_ERROR` with the stack trace only logged, never returned (`application.yaml` disables stacktrace/exception/message in Spring's default error body).

## Extension architecture

Manifest V3, three build entry points (`vite.config.ts`): `sidepanel` (React UI), `service-worker` (background), `content-script`.

- **Service worker** (`src/background/service-worker.ts`) is the sole message broker: it handles `GET_ACTIVE_TAB` and `ANALYZE_CURRENT_PAGE` requests from the side panel, injects the content script on demand (`ensureContentScript`, pinging first to avoid double-injection), calls `analysis-api.ts` to hit the backend, and reports progress stages (`EXTRACTING`/`ANALYZING`) back via `chrome.runtime.sendMessage`.
- **Content script** (`src/content/page-extractor.ts` + `selector.ts`) extracts visible text nodes (capped at 300, deduped, 8–1000 chars each) and image URLs (capped at 50) from the page, generating a CSS selector per text node for the side panel to reference back to.
- **Side panel** (`src/sidepanel/App.tsx`) is a single-view state machine (`IDLE → EXTRACTING/ANALYZING → SUCCESS/ERROR`) driven by responses from the service worker.
- Message contracts live in `src/types/message.ts`; all cross-context messages are runtime-validated with type guards (`isSidePanelRequest`, `isPageExtractionResult`, etc.) since `chrome.runtime`/`chrome.tabs` messaging isn't statically typed.

**Known gap**: the side panel currently treats any successful message-passing round-trip as `SUCCESS` and renders `analysis.findings` directly — it does not yet handle the backend's `PENDING`/`PROCESSING` statuses by polling for the final result. Since the backend now processes analyses asynchronously (see above), a first-time request against a given page will return `PENDING` with empty findings today rather than the analyzed result. This needs polling/re-submit logic in the service worker or side panel before the async backend work is fully usable end-to-end.

**Known issue — no automated coverage for the Backend response contract**: the extension has zero automated tests (`tsc --noEmit` is the only check), so the *logical* correctness of the Backend response contract (e.g. which fields are required for a given `status`) is never verified — TypeScript's type checker only confirms the code compiles against the declared `AnalysisResponse` type, not that a runtime type guard like `isAnalysisResponse()` (`src/api/analysis-api.ts`) actually implements that contract correctly. Bugs in these `unknown`-based runtime guards only surface by manually exercising the extension in real Chrome; a curl/Postman check against the Backend alone is not enough, since that only confirms the JSON shape, not how the Extension parses it. Case in point (2026-09-16): `isAnalysisResponse()` required `summary`/`findings` to always be present and non-null, but the Backend intentionally sends `summary: null` for `PENDING`/`PROCESSING`/`FAILED` (only `COMPLETED` populates `summary`) — every curl-based check passed, and the mismatch was only caught when polling failed in a real Chrome side panel session. **When the Backend's `AnalysisResponse` contract changes, verify with curl AND manually re-run all four `status` values (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`) through the actual Extension in Chrome** — a curl check alone will not catch a parsing mismatch like this one.

## Conventions

- Backend: constructor injection everywhere (no field `@Autowired`), no Lombok getters/setters on entities/DTOs (Lombok is on the classpath but only lightly used — check before assuming it's idiomatic here). Multi-outcome results are modeled as sealed interfaces with record variants matched via `switch` (see `AnalysisResultResolution`, `AnalysisSubmissionResult`).
- User-facing and validation error messages are Korean; identifiers, comments, and commit messages mix Korean/English (recent commit subjects are Korean `feat:`/`fix:` conventional-commit style).
- Tests mirror the main package structure 1:1 under `src/test/java`; prefer adding a test in the matching package over a top-level catch-all.
- Flyway migrations are additive and forward-only (`V{n}__description.sql`); don't edit an already-applied migration file — add a new one.

## Current state / in-progress work

- Working on branch `feature/analysis-orchestration` (13 commits ahead of `main`), building out the async analysis pipeline and result-reuse logic described above.
- `data/`, `adcheck_rule_engine_data.dump`, and `backend/src/main/java/com/adcheck/analysis/repository/.cph/` are untracked local artifacts (rule-engine seed CSVs, a DB dump, and a stray Competitive-Programming-Helper cache dir) — not part of the source tree; don't `git add` them.
- The extension/backend integration gap noted above (polling for async results) is the most likely next piece of work once the backend orchestration lands.
