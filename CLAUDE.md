# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

AdCheck helps consumers check the advertising claims on health-supplement product pages before buying. It is a monorepo with two independent projects:

- `backend/` — Spring Boot 4 (Java 21) analysis API
- `extension/` — Manifest V3 Chrome Extension (React + TypeScript, built with Vite)

The backend runs a real multi-stage AI pipeline (`GeminiClaimAnalyzer` → `FindingAssembler`). `MockClaimAnalyzer` still exists as a keyword-matcher stand-in but is **not** what runs by default — see "Analysis pipeline stages" below.

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
3. `AnalysisBackgroundJob.process()` is submitted to a dedicated executor (`@Async(AnalysisAsyncConfiguration.EXECUTOR_NAME)`, not the default Spring async pool) and runs `ClaimAnalyzer` (`GeminiClaimAnalyzer` in normal operation; `MockClaimAnalyzer` is a stand-in kept for tests), then persists the result (completed) or failure via `AnalysisLifecycleService`. If the executor's queue is full, `TaskRejectedException` is translated into `AnalysisQueueFullException` and the pending row is marked failed rather than left stuck in PENDING.
4. The initial HTTP response for a new/in-progress analysis carries no results (`PENDING`/`PROCESSING`, empty findings) — the client is expected to poll `POST /api/v1/analyses` again with the same request to eventually get the `Reused` result once processing completes.

`AnalysisAsyncConfiguration.Async` properties (`adcheck.analysis.async.*`: core-pool-size, max-pool-size, queue-capacity, thread-name-prefix) are validated at startup via Jakarta Bean Validation (`@Min`, `@NotBlank` on `AnalysisProperties.Async`) plus a manual `core-pool-size <= max-pool-size` check in the executor's `@Bean` method — invalid config fails application startup rather than silently degrading (e.g. `queueCapacity <= 0` would otherwise make Spring silently swap in a `SynchronousQueue`).

### Result codecs

Completed results are stored as JSON in the `analyses.result_json` column and round-tripped through `AnalysisResultSnapshotMapper`/`AnalysisResultJsonCodec` — when changing `AnalysisResponse`/`FindingResponse` shapes, check whether old stored `result_json` rows still deserialize (there's no versioned migration for stored JSON payloads).

### Analysis pipeline stages

`FindingAssembler.assemble()` is the real orchestrator. Order matters — each stage depends on the previous one's output:

1. **AI#1 extraction** (`GeminiClaimAnalyzer` → `ProductContentExtractionService`) — one Gemini call pulls claims / product candidates / ingredient candidates / risk signals out of body text + OCR text. Image OCR runs first via `FallbackOcrService` (Google Vision first, Gemini as fallback).
2. **Product + ingredient confirmation** — extracted candidates are matched against `products` / `ingredient_master`. Use `IngredientMatchingService.matchField()` for an ingredient *table*, not `match()`, which expects a single ingredient (passing a whole table to `match()` silently yielded 0 confirmations for a long time).
3. **Official-function quotation filter** (`OfficialFunctionQuotationDetector`) — drops claims that merely reproduce the confirmed ingredients' officially recognised wording, since those are label text, not ad copy. **Only the confirmed ingredients' wording is used as the comparison set**; that narrowness is a safety constraint, not an optimisation — a page quoting an ingredient it does not contain stays in as a genuine violation. Any change here must keep the "violation misread as quotation" count at 0 (`OfficialFunctionQuotationDetectorTest`).
4. **Rule judgment** (`RuleAnalysisService.analyzeAll()`) — batched along the *rule* axis, so call count is the number of rules, not rules × claims. Each claim comes back MATCHED / REVIEW_REQUIRED / NOT_MATCHED.
5. **RAG** (`RagRetrievalService`) and **AI#2 comparison** (`GeminiClaimComparisonService`) — **only for claims with at least one MATCHED rule.** Claims with only REVIEW_REQUIRED skip both and get the fixed message "확인이 필요한 표현입니다." This asymmetry is the reason several fields look empty in practice; check it before assuming a matching bug.
6. **Finding assembly** — `riskLevel`/`category`/`sources` come from one representative rule (`mostSevere()`), while `rules` carries every judged rule. Adding to `rules` is safe; changing the representative-rule fields is not.

Rate limit worth knowing: one analysis costs ~12 Gemini calls and the free tier allows 15 RPM, so **two analyses inside one minute always trigger 429** and the retry delay (~45s) inflates the whole analysis. Space timing measurements at least a minute apart.

### Error handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) is the single place mapping exceptions to `ApiErrorResponse`. Bean validation failures on `CreateAnalysisRequest` become `VALIDATION_ERROR` with per-field messages; anything unhandled becomes a generic `INTERNAL_SERVER_ERROR` with the stack trace only logged, never returned (`application.yaml` disables stacktrace/exception/message in Spring's default error body).

## Extension architecture

Manifest V3, three build entry points (`vite.config.ts`): `sidepanel` (React UI), `service-worker` (background), `content-script`.

- **Service worker** (`src/background/service-worker.ts`) is the sole message broker: it handles `GET_ACTIVE_TAB` and `ANALYZE_CURRENT_PAGE` requests from the side panel, injects the content script on demand (`ensureContentScript`, pinging first to avoid double-injection), calls `analysis-api.ts` to hit the backend, and reports progress stages (`EXTRACTING`/`ANALYZING`) back via `chrome.runtime.sendMessage`.
- **Content script** (`src/content/page-extractor.ts` + `selector.ts`) extracts visible text nodes (capped at 300, deduped, 8–1000 chars each) and image URLs (capped at 50) from the page, generating a CSS selector per text node for the side panel to reference back to.
- **Side panel** (`src/sidepanel/App.tsx`) is a single-view state machine (`IDLE → EXTRACTING/ANALYZING → SUCCESS/ERROR`) driven by responses from the service worker.
- Message contracts live in `src/types/message.ts`; all cross-context messages are runtime-validated with type guards (`isSidePanelRequest`, `isPageExtractionResult`, etc.) since `chrome.runtime`/`chrome.tabs` messaging isn't statically typed.

**Polling is implemented** (`pollUntilFinished` in `src/background/service-worker.ts`): the service worker polls `GET /api/v1/analyses/{id}` every 1.5s up to 40 times (60s timeout) until `COMPLETED` or `FAILED`. Note `FAILED` comes back to the side panel inside an `ok: true` envelope, not as an error — `useAdCheck.ts` catches `response.status === "FAILED"` explicitly and routes it to the `ERROR` view. Without that branch a failed analysis would render as the "no problems found" screen.

**Known gap — the `ERROR` view conflates three causes**: `IdleView`'s error variant shows one fixed message ("분석 서버에 연결할 수 없어요"), but it is reached by `BACKEND_UNAVAILABLE` (backend really is down), `ANALYSIS_TIMEOUT` (60s polling exceeded) and `status: "FAILED"` (server responded fine, the analysis itself failed). Only the first is accurate. Side panel UI is another teammate's area, so this is a discussion item rather than a fix to make unilaterally.

**Known issue — no automated coverage for the Backend response contract**: the extension has zero automated tests (`tsc --noEmit` is the only check), so the *logical* correctness of the Backend response contract (e.g. which fields are required for a given `status`) is never verified — TypeScript's type checker only confirms the code compiles against the declared `AnalysisResponse` type, not that a runtime type guard like `isAnalysisResponse()` (`src/api/analysis-api.ts`) actually implements that contract correctly. Bugs in these `unknown`-based runtime guards only surface by manually exercising the extension in real Chrome; a curl/Postman check against the Backend alone is not enough, since that only confirms the JSON shape, not how the Extension parses it. Case in point (2026-09-16): `isAnalysisResponse()` required `summary`/`findings` to always be present and non-null, but the Backend intentionally sends `summary: null` for `PENDING`/`PROCESSING`/`FAILED` (only `COMPLETED` populates `summary`) — every curl-based check passed, and the mismatch was only caught when polling failed in a real Chrome side panel session. **When the Backend's `AnalysisResponse` contract changes, verify with curl AND manually re-run all four `status` values (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`) through the actual Extension in Chrome** — a curl check alone will not catch a parsing mismatch like this one.

## Conventions

- Backend: constructor injection everywhere (no field `@Autowired`), no Lombok getters/setters on entities/DTOs (Lombok is on the classpath but only lightly used — check before assuming it's idiomatic here). Multi-outcome results are modeled as sealed interfaces with record variants matched via `switch` (see `AnalysisResultResolution`, `AnalysisSubmissionResult`).
- User-facing and validation error messages are Korean; identifiers, comments, and commit messages mix Korean/English (recent commit subjects are Korean `feat:`/`fix:` conventional-commit style).
- Tests mirror the main package structure 1:1 under `src/test/java`; prefer adding a test in the matching package over a top-level catch-all.
- Flyway migrations are additive and forward-only (`V{n}__description.sql`); don't edit an already-applied migration file — add a new one.

## Current state / in-progress work

- Working on branch `feature/analysis-orchestration` (13 commits ahead of `main`), building out the async analysis pipeline and result-reuse logic described above.
- `data/`, `adcheck_rule_engine_data.dump`, and `backend/src/main/java/com/adcheck/analysis/repository/.cph/` are untracked local artifacts (rule-engine seed CSVs, a DB dump, and a stray Competitive-Programming-Helper cache dir) — not part of the source tree; don't `git add` them.
- The open thread is that `REVIEW_REQUIRED` ("could not judge") is rendered with the rule's own severity, so it shows as HIGH alongside real violations — 63 of 86 such findings survive the quotation filter. Investigate why `C05`/`C07`/`C08`/`C09`/`C24` abstain on nearly every claim before changing how it is displayed.
- Measurement fixtures live in `backend/src/test/resources/` (`official-functions.tsv`, `stored-claims.txt`, `review-only-claims.txt`), exported from the local Postgres. They back the quotation-filter tests; regenerate them rather than hand-editing.
