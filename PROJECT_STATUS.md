# AdCheck 프로젝트 현황 (2026-09-23 기준, `feature/extension-api-test` 브랜치)

> 이 문서는 다른 AI 대화에 프로젝트 맥락을 전달하기 위해 작성됨. 모든 수치는 해당 절이 작성된 시점에 직접 재실행/재확인한 값.
>
> **절마다 기준 시점이 다르다.** 2~5번과 7번은 2026-09-18(`feature/extension-real-api-connection`, 커밋 `e77c5f1`) 시점에 작성됐고 그 뒤로 재확인하지 않았다 — 특히 4번(팀원 브랜치 현황)은 그 사이 여러 번 바뀌었을 수 있으니 그대로 믿지 말고 `git log`로 다시 확인할 것. 6번과 8번은 2026-09-23에 갱신했다.

## 1. 프로젝트 한 줄 요약

**AdCheck**는 소비자가 건강기능식품을 온라인으로 구매하기 전에, 상품 상세페이지의 광고 문구가 식약처 기준(건강기능식품 표시·광고 규정)을 위반하는지 미리 확인해주는 Chrome 확장 프로그램 서비스다. 모노레포 구조로 `backend/`(Spring Boot 4, Java 21 분석 API)와 `extension/`(Manifest V3 Chrome 확장, React+TypeScript+Vite) 두 독립 프로젝트로 구성된다.

**팀 구성** (git 커밋 이력 기준):
| 이름 | 담당 | 브랜치 |
|---|---|---|
| juwon717(대화 상대, 이 브랜치 작성자) | Backend 파이프라인 오케스트레이션 + Extension 실제 API 연동 | `feature/extension-real-api-connection` (현재 브랜치) |
| 서호준 (git: `tjghwns`) | Rule Judge(AI#3, AiRuleEvaluator) — 규칙 판정 AI, 성능/안정성 | `feature/rule-judge-ai` |
| 송하은 (git: `송하은`) | Design + Frontend sidepanel UI | `refactor/sidepanel-views` |
| (git: `leejjaei`, 실명 미확인) | 상품 정보 추출 로직(마켓별 DOM 대응), CSV 기록 도구 | `feature/extension-extraction` |

## 2. 전체 아키텍처

```
[Chrome Extension]                          [Backend: Spring Boot]                [PostgreSQL]
side panel(React)                                                                  analyses 테이블
   │ ANALYZE_CURRENT_PAGE                                                          (result_json JSONB)
   ▼
service-worker.ts (메시지 브로커)
   │ EXTRACT_PAGE
   ▼
content-script.ts → page-extractor.ts (DOM/이미지 추출, dedup)
   │ POST /api/v1/analyses (texts, images)
   ▼
                                          AnalysisController → AnalysisService.analyze()
                                              → AnalysisResultResolver (재사용 판정)
                                              → AnalysisLifecycleService.createPending() (PENDING insert)
                                              → AnalysisBackgroundJob.process() (@Async, 여기서부터 파이프라인)
service-worker.ts가 GET으로 폴링(1.5초×최대40회=60초) ◄── HTTP 202/200 (PENDING/PROCESSING/COMPLETED)
```

**핵심 AI 파이프라인** (`AnalysisBackgroundJob.process()` 내부, 순서대로):

| 단계 | 담당 클래스 | 역할 |
|---|---|---|
| AI#1 추출 | `GeminiClaimAnalyzer` → `ProductContentExtractionService` | 본문+OCR 텍스트에서 claims/productCandidates/ingredientCandidates/riskSignalCandidates를 Gemini 호출 1회로 동시 추출. 이미지 OCR은 `GeminiOcrService` |
| Product/Ingredient 확정 | `ProductIdentificationService`, `ProductIngredientQueryService`, `IngredientMatchingService` | 추출된 후보를 DB(`products`, `ingredient_master` 등)와 대조해 확정 |
| Rule Engine | `RuleAnalysisService` → `RuleSelector` → `RuleEvaluatorRegistry` → `CommonRuleEvaluator`/`LiteralRuleEvaluator`/`AiRuleEvaluator` | claim마다 적용 가능한 규칙(COMMON 30개 + 원료별 최대 41개)을 조회해 MATCHED/REVIEW_REQUIRED/NOT_MATCHED 판정 |
| RAG | `RagRetrievalService.searchBatch()` | MATCHED claim들의 근거 문단을 배치 조회(judgment 단계 grounding은 팀 결정으로 끔 — 아래 5번 참고) |
| AI#2 비교 | `GeminiClaimComparisonService.compare()` | MATCHED claim마다 개별 호출(배치 시도했으나 포기, 6번 참고)해 최종 설명문 생성 |
| Finding 조립 | `FindingAssembler.assemble()` | 위 전부를 오케스트레이션. MATCHED→AI#2, REVIEW_REQUIRED(UNSUPPORTED_RULE 제외)→고정메시지 1개, 전부 NOT_MATCHED→Finding 없음 |
| 결과 저장 | `AnalysisResultSnapshotMapper`/`AnalysisResultJsonCodec` | `analyses.result_json`(JSONB)에 저장 |

Extension 쪽 핵심 파일: `service-worker.ts`(메시지 브로커+폴링), `content-script.ts`+`page-extractor.ts`(DOM/이미지 추출), `api/analysis-api.ts`(HTTP 통신+런타임 타입가드), `sidepanel/hooks/useAdCheck.ts`(상태머신 훅), `sidepanel/App.tsx`+`views/*`(렌더링).

## 3. 지금 이 브랜치에 실제로 구현되어 있는 것 (전수 확인, 작성 시점 재검증)

### Backend

| 기능 | 상태 | 검증 근거 |
|---|---|---|
| 재사용(TTL) 판정 | ✅ 구현 | `AnalysisResultResolver` + `reuse-ttl-clean=30d`/`reuse-ttl-with-finding=7d`. 동일 URL+내용 재요청 시 캐시 히트로 즉시 200 COMPLETED 반환되는 것 실측 확인 |
| 비동기 Job + GET polling | ✅ 구현 | `AnalysisBackgroundJob`(`@Async`) + `GET /api/v1/analyses/{id}`. 실측 타이밍: claim 1개 ~10초, claim 4개(전부 MATCHED) ~7.5초, AiRuleEvaluator 도입 후 claim 8개 기준 최대 ~2분30초까지 증가(6번 참고) |
| Rule Engine | ✅ 구현 | `CommonRuleEvaluator`(C05/C07/C24 정규식) + `LiteralRuleEvaluator` + `AiRuleEvaluator`, 28개 규칙 파일럿 등록. `RuleEnginePostgresTest`로 실제 Postgres(71개 규칙: COMMON 30 + INGREDIENT_SPECIFIC 41) 검증 |
| AI 4블록(추출/원료매칭/RAG/비교) | ✅ 구현 | 전부 실제 Gemini API로 curl 테스트 완료(mock 아님) |
| Finding 조립 어댑터 | ✅ 구현 | `FindingAssembler` — MATCHED→AI#2, REVIEW_REQUIRED는 `reasonCode=UNSUPPORTED_RULE`(평가기 미구현) 제외 후 남은 것 중 최고severity 1개만, 전부 제외되면 Finding 미생성 |
| **전체 테스트** | **277개 / 실패 0 / 에러 0 / 스킵 16** | `./gradlew.bat test` 방금 재실행, `BUILD SUCCESSFUL` |

### Extension

| 기능 | 상태 | 검증 근거 |
|---|---|---|
| 실제 API 연동(Mock 제거) | ✅ 구현 | `useAdCheck.ts`의 `analyze()`가 `testTarget==="NORMAL"`일 때 실제 서버 호출(SAFE/ERROR/INVALID 테스트 스위치는 의도적으로 mock 유지) |
| DOM/이미지 추출 | ✅ 구현 | `page-extractor.ts` — Naver SmartEditor 등 마켓별 상세영역 인식, lazy-load 스크롤 대기, 추천상품/리뷰 이미지 제외 |
| 중복 제거(dedup) | ✅ 구현 | 텍스트/이미지 둘 다 완전일치(공백 정규화 후) 기준 dedup. 순수함수로 분리 + vitest 유닛테스트 8개 신규 도입, 전부 통과 |
| 폴링 | ✅ 구현 | `service-worker.ts`의 `pollUntilFinished()` — 1.5초 간격 × 최대 40회(=60초 타임아웃) |
| 상태별 화면 분기 | ✅ 구현 | `IDLE/ANALYZING/SUMMARY_HERO/EMPTY/BUBBLE_PREVIEW/DETAIL_LIST/UNSUPPORTED/ERROR` 8개 상태를 `App.tsx`가 분기 |
| **빌드/테스트** | **둘 다 통과** | `npm test`(vitest 8/8), `npm run build`(tsc+vite build 성공) |

**실제 상품 페이지 검증**: 실제 네이버 계열 상품페이지(갱년기 건강기능식품, 실제 URL은 이 문서에 임의 기재하지 않음)로 여러 차례 재분석 실측 — findingCount가 파이프라인 변경마다 24→7→8→4 등으로 달라지는 현상을 확인했고, 조사 결과 이건 추출 단계 중복이 아니라 **Rule Judge(AI#3) 판정 자체의 비결정성**이 원인으로 판단됨(6번 참고).

## 4. 지금 진행 중인 것 (다른 팀원 작업, 아직 병합 안 됨)

### 서호준 — `feature/rule-judge-ai` (최신 `2318ffe`)

이미 28개 규칙 파일럿 버전은 이 브랜치에 병합됨(`e77c5f1`). **이후 미병합 신규 커밋 4개**:
```
2318ffe perf: OCR 청크 병렬화 + 초대형 이미지 제외 — 25.3초 → 14.9초
d46a054 perf: OCR 이미지 다운로드 병렬화 — 12장 기준 7.4초 → 0.85초
fed2333 perf: 분석 소요시간 23.5초 → 8.9초, 429로 인한 Claim 유실 해결
7df8495 feat: Rule Judge 파일럿 28→32개 확장, 프롬프트 2차 개선 시도 기록
```
**겹치는 파일(재병합 시 확인 필요)**: `FindingAssembler.java`(144줄 변경 — 이 브랜치에서 UNSUPPORTED_RULE 필터링을 직접 만졌던 파일), `RuleAnalysisService.java`, `RuleEvaluator.java`, `RuleEvaluatorRegistry.java`, `AiRuleEvaluator.java`, `GeminiClient.java`, `GeminiOcrService.java`, `RuleJudgeProperties.java`.

### 송하은 — `refactor/sidepanel-views` (최신 `4a527a9`)

**실제 `git merge` 이력이 한 번도 없음** — 예전엔 파일 내용만 수동 동기화(`d9d43e6` 시점까지). **이후 미병합 신규 커밋 3개**:
```
8c57a5c feat: polish loading/history/detail UX and drop server-error screen
37d5b15 feat: retire server-error path, retry-in-place, and gauge/graphic polish
4a527a9 fix: shrink loading screen tip text back down a notch
```
**겹치는 파일**: 22개, 전부 `extension/src/sidepanel/` 안. 실제 `--no-commit --no-ff` 시뮬레이션 결과 **22개 파일 전부 충돌**(대부분 `CONFLICT (add/add)` — 실제 merge 이력이 없어서 발생) 확인 후 abort로 원복함. 특히 `useAdCheck.ts`/`types.ts`/`App.tsx`/`IdleView.tsx`가 이 브랜치의 실제 백엔드 연동/에러처리 코드와 정면 충돌.

**⚠️ 중요 이슈**: 송하은님 쪽에서 "서버오류(ERROR)" 전용 화면과 상태값 자체를 삭제함(`types.ts`의 `ViewStatus`/`TestTarget`에서 `"ERROR"` 제거, `IdleView.tsx`의 에러 분기 제거). 그대로 가져오면, 이 브랜치가 실제 백엔드 오류(서버 다운/타임아웃 등)를 처리할 때 쓰는 `setStatus("ERROR")` 호출이 갈 곳을 잃어 **화면이 빈 채로 뜨는 문제**가 생김 — 병합 전 팀 논의 필요.

### (실명 미확인, git: leejjaei) — `feature/extension-extraction` (최신 `fb69aab`)

Naver 대응 추출 로직(`page-extractor.ts`)의 원본 출처이며, 이미 이 브랜치에 이식·반영됨. **이후 미병합 신규 커밋 1개**:
```
fb69aab fix(extension): 마켓별 상세 추출 및 후기·추천 텍스트 제외 (v0.1.10)
```
새 파일 `marketplace-detail.ts` 추가, `page-extractor.ts`/`content-script.ts` 수정(126줄/15줄) — 겹치는 파일이라 재이식 필요.

## 5. 오늘 발견하고 해결한 주요 버그

| 버그/이슈 | 증상 | 원인 | 해결 |
|---|---|---|---|
| `isAnalysisResponse()` 파싱 실패 | 폴링 중 PENDING/PROCESSING 응답마다 "응답 형식을 확인할 수 없습니다" 에러 | Extension 타입가드가 `summary`/`findings`를 항상 non-null로 요구했는데, Backend는 `COMPLETED` 전엔 `summary:null`로 보냄 | status별 조건부 검증으로 재작성(`analysis-api.ts`) |
| REVIEW_REQUIRED Finding 정보 손실 | 여러 규칙이 REVIEW_REQUIRED여도 Finding 1개만 나오고 나머지 정보 소실 | `mostSevere()`가 대표 하나만 선택 | 1차: category 그룹핑+상한 3개 → 2차: 아래 항목과 함께 재설계 |
| REVIEW_REQUIRED 대부분이 "가짜"(UNSUPPORTED_RULE) | claim 내용과 무관하게 거의 매번 같은 카테고리(C01/C02/C03)가 REVIEW_REQUIRED로 뜸 | COMMON 규칙 30개 중 당시 27개가 평가기 미구현이라 무조건 REVIEW_REQUIRED 처리됨 | `reasonCode=UNSUPPORTED_RULE` 필터링 후 남은 게 없으면 Finding 자체를 안 만듦 |
| 콘솔 한글 로그 깨짐 | 한글 로그(예: "RAG 청크 N개 로드 완료")가 깨져서 출력 | `bootRun`이 IDE에서 직접 실행돼 `build.gradle`의 `bootRun` 전용 인코딩 설정이 적용 안 됨 | `logback-spring.xml`에 `CONSOLE_LOG_CHARSET=UTF-8` 명시(실행 방식과 무관하게 항상 적용) |
| (조사 결과 버그 아님으로 판명) findingCount=24 중복 의심 | 실제 페이지 재분석 시 같은 문구가 반복되는 것처럼 보임 | 실제로는 텍스트 중복이 아니라, 서로 다른 8개 claim 각각이 REVIEW_REQUIRED 상한(3개)만큼 Finding을 만들어 24개가 된 것 | dedup 로직 자체는 이미 정상 동작 중이었음을 확인, 방어적으로 순수함수 분리 + 유닛테스트만 추가 |

## 6. 알려진 미해결 이슈 / 다음 단계

- **Rule Judge(AI#3) 판정 비결정성**: 같은 claim+같은 규칙 조합도 재현성이 100%가 아님. `docs/troubleshooting.md`에 팀이 직접 실측 기록(`needsOutsideContext` 게이트가 LLM 샘플링 기반이라 확률적). 실제로 같은 페이지를 여러 번 재분석했을 때 claim 구성/판정 결과가 매번 달라지는 현상을 실측으로 재확인함.
- **~~원료 DB 미매칭 케이스~~ → 해결(2026-09-23)**: `officialFunctionMatchedCount`가 거의 항상 0이던 원인은 매칭 실패가 아니라 `FindingAssembler.resolveIngredients`가 원료표를 통째로 `match()`에 넘긴 구조 버그였다. `matchField().items()`로 바꾸자 같은 페이지에서 원료 확정 0→4건, 공식 기능성 0→19건이 되고 M01~M04 규칙이 활성화됐다.
- **규칙 평가기가 판단 보류(REVIEW_REQUIRED)를 남발함 (조사 예정)**: `C05_FUNCTION_EXCEED`·`C07_ABSOLUTE_EFFECT`·`C08`·`C09`·`C24`가 사실상 모든 Claim에 대해 "지원 문장 범위에서 확정할 수 없습니다"를 낸다. 저장된 Finding 143건 중 109건(76%)이 REVIEW_REQUIRED-only였고, 표시란 제외 필터를 넣어도 86건 중 63건(73%)이 그대로 남았다. **`REVIEW_REQUIRED`는 "위반"이 아니라 "판단 불가"인데 규칙의 severity를 그대로 써서 HIGH로 표시**되므로, 무해한 섹션 제목(`안에서부터 맑고 환하게 시작하는 하루`)이 실제 위반과 같은 등급으로 뜬다. 원인 조사가 우선이고, 안 풀리면 표시 정책(위험도 강등 / 별도 섹션 분리)을 팀과 논의한다.
- **폴링 타임아웃 값 조정 필요성**: 현재 60초인데, AiRuleEvaluator 도입 후 claim 8개 기준 처리시간이 최대 ~2분30초까지 늘어난 사례가 있었음. 서호준님 쪽 미병합 커밋(429 수정/병렬화)이 8.9초까지 줄였다고 하니, 그게 병합되면 재측정 필요.
- **유료 API 전환 논의 필요성**: Gemini 무료 티어 한도(`generate_content` 분당 15회, `embed_content` 분당 100회+일일 1,000회, 배치 100건 제한 등)가 실사용 스케일의 병목. 상세페이지 1개당 15~20회 호출, 무료 티어 기준 하루 5~6페이지가 한계라는 계산이 나온 적 있음(서호준님 병렬화 작업으로 개선 진행 중).
- **브랜치 3개 최종 통합 계획**: `rule-judge-ai`(신규 4커밋, `FindingAssembler` 등 8개 파일 겹침), `refactor/sidepanel-views`(신규 3커밋, 22개 파일 겹침 — 특히 ERROR 화면 삭제 이슈로 팀 논의 필요), `feature/extension-extraction`(신규 1커밋, `page-extractor.ts` 겹침) — 전부 미병합. 병합 순서와 충돌 해결 방식을 아직 정하지 않음.

## 7. 최근 커밋 히스토리 (`git log --oneline -20`, 이 브랜치 기준)

```
e77c5f1 merge: rule-judge-ai(28개 규칙 등록) 병합, FindingAssembler는 UNSUPPORTED_RULE 필터링 기준으로 통합
f481570 fix: 콘솔 로그 한글 인코딩 깨짐 수정 (JavaCompile/bootRun UTF-8 강제 + logback 콘솔 charset 설정)
72f33fe fix: REVIEW_REQUIRED 필터링 - UNSUPPORTED_RULE(미구현 규칙) 제외, 진짜 평가된 것만 노출
13a0767 feat: Rule Judge 파일럿 28개 규칙 등록 + RAG grounding 끔 + 검증 방법론 정리   ← 서호준님 작업 병합
524025b feat: page-extractor에 텍스트/이미지 중복 제거(dedup) 로직 추가
9891cf6 fix: isAnalysisResponse 조건부 검증 + 추출 로직 개선
8ed6294 fix: REVIEW_REQUIRED Finding이 category별로 분리되도록 수정 (기존엔 첫 번째 규칙만 반영되어 정보 손실 발생)
80a8d02 fix: host_permissions에 <all_urls> 추가, 모든 사이트에서 분석 가능하도록
00ef183 feat: useAdCheck.ts 실제 Backend API 연동
dc94514 feat: LiteralRuleEvaluator 초안 - 리터럴형+혼합 11개 규칙 정규식화
3988c31 feat: AiRuleEvaluator 프롬프트 초안 - 해석형+혼합 39개 규칙 대상
be1aaee docs: Rule Judge용 검증 데이터셋 추가 (해석형+혼합 39개 규칙)
2ef90da chore: 스크래치/아티팩트 작업 폴더 gitignore 추가
1657bcb chore: 배치 검증 결과 로그 파일 gitignore 추가
5e4bce4 feat: AI#1 context 판단 결과를 ClaimContextClassifier에 반영
1ba746b Enhance README with branch strategy and current branches
a6f6956 feat: Finding 조립 어댑터 구현 - Backend 파이프라인 실제 연결 완료
a16c12e feat: AI#1/원료매칭/RAG/AI#2 이식 및 ClaimAnalyzer 계약 전환
c506492 feat: GEMINI_API_KEY 조건부 등록 인프라 구현
6685965 feat: GET /api/v1/analyses/{id} 상태 조회(polling) 엔드포인트 구현
d469391 feat: Flyway V4/V5 데이터 로더 이식 및 FINAL 데이터 정합성 수정
52f4a62 feat: 상품별 위반 이력 기반 차등 TTL 구현
a84a1c2 merge: integration/rule-engine-base를 analysis-orchestration에 통합
4b0ebb9 docs: CLAUDE.md 신규 작성 - 프로젝트 컨텍스트 및 알려진 이슈 정리
eef94bf feat: 분석 작업 비동기 처리 및 Executor 설정 검증 추가
```

총 44개 커밋(이 브랜치). `dc94514`~`be1aaee`는 rule-judge-ai 브랜치 병합으로 같이 들어온 서호준님의 초기 작업 이력.

## 8. 2026-09-23 작업 요약 (`feature/extension-api-test`)

이날 작업은 **Finding 다중 근거(`rules`) 추가 → 실제 크롬 E2E 검증 → 그 과정에서 드러난 문제 추적**의 순서로 이어졌다. 상세 경위와 수치는 `docs/troubleshooting.md`, 판정 편차는 `docs/rule-judge-instability-log.md`에 있다.

| 작업 | 내용 | 결과 |
|---|---|---|
| `Finding.rules` | 대표 규칙 하나만 내려주던 것에 판정된 규칙 전체를 **추가**. 기존 필드는 그대로라 무시하면 이전과 동일 동작 | 규칙 5~6개가 전부 판단 보류였다는 사실이 드러남 — 이하 작업의 출발점 |
| E2E 4개 status | `PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`를 실제 크롬에서 통과. `FAILED`는 DB 행을 폴링 중 뒤집어 확인 | 전부 통과. 단 에러 화면 문구가 세 원인을 구분 못 함(프론트 공유 항목) |
| 표시란 제외 (프롬프트) | AI#1이 법정 기능성 표시란을 광고로 뽑던 것을 제외 기준 추가로 차단 | `[원료명] 문구` 형태는 차단. 추출 안정도 100% 유지. **머리말 없는 표시란은 못 잡음** |
| 표시란 제외 (DB 대조) | `OfficialFunctionQuotationDetector` 신규 — 겉모양 대신 공식 문구 483종과 잔여물 검사로 대조 | 인용 인식률 96.9%, **위반 오판 0/3,240** |
| `officialFunction` 보완 | AI#2만 채우던 구조(143건 중 3건=2%)에 결정론적 보완 추가. LLM 호출 0회 | 구조적 불가능(76%) 제거 |

**설계상 중요한 결정 두 가지**

1. **표시란 필터의 대조 범위를 "확정 원료의 공식 문구"로 묶었다.** 이건 성능이 아니라 안전을 위한 제약이다. 실제로 홍삼 문구를 쓴 페이지에서 홍삼이 확정 원료가 아니어서 필터가 통과시켰고, 그 결과 "자기 제품에 없는 원료의 효능 표방"이라는 **진짜 위반이 살아남았다**.
2. **위반을 숨길 수 있는 필터는 양방향으로 검증하고, 두 방향의 엄격도를 다르게 뒀다.** "인용을 못 알아봄"은 현상 유지라 비율만 기록하고, "위반을 인용으로 오판"은 0건으로 단언한다. 같은 기준으로 두면 숫자를 맞추려고 안전 쪽을 깎게 된다.

**연속 분석 시 느려지는 것은 파이프라인 문제가 아니다** — 분석 1건 = Gemini 12회인데 무료 티어가 15 RPM이라, 1분 안에 두 페이지를 돌리면 429 재시도 대기(44.5초)가 붙는다. 성능을 측정할 때는 분석 간격을 1분 이상 띄워야 한다.
