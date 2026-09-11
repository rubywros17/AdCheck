# Rule Engine 첫 구현

`RuleAnalysisService.analyze(RuleAnalysisRequest)`는 한 Claim에 대해 DB의 COMMON Rule과 Backend가 확정한 IngredientMaster ID에 연결된 원료별 Rule을 선택하고, 각 평가와 실제 공식 출처 metadata를 반환한다. 새 endpoint, 의존성, schema, CSV 변경은 없다. 기존 `ClaimAnalyzer`/`MockClaimAnalyzer`와 분석 API에는 연결하지 않았다.

## 로컬 환경과 DB 준비

Java toolchain은 21, 기존 Gradle Wrapper는 9.7.1이다. macOS Homebrew 설치 예시:

```sh
brew install openjdk@21 postgresql@17
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$(brew --prefix postgresql@17)/bin:$JAVA_HOME/bin:$PATH"
java -version
psql --version
cd backend
bash gradlew --version
```

현재 Wrapper 파일은 실행 권한이 없으므로 `bash gradlew`를 사용한다. 시스템 Java 링크나 전역 shell 설정 변경은 필수가 아니다. `pg_isready -h localhost -p 5432`로 기존 서버를 확인한 후, 미실행 상태일 때만 로컬 클러스터를 시작한다. Homebrew 기본 클러스터 예시:

```sh
pg_ctl -D /opt/homebrew/var/postgresql@17 -l /private/tmp/adcheck-postgresql.log start
psql -X -h localhost -U "$USER" -d postgres
```

관리자 이름은 설치마다 다르다. 이번 Homebrew 신규 설치의 관리자는 OS 사용자이며 로컬 인증은 기본 `trust`였다. 따라서 이번 접속 성공은 비밀번호 인증 검증을 의미하지 않는다. 기존 인증 설정을 변경하지 않았다.

psql에서 먼저 존재 여부를 확인한다:

```sql
SELECT datname FROM pg_database WHERE datname = 'adcheck';
SELECT rolname FROM pg_roles WHERE rolname = 'adcheck_service';
```

없을 때만 아래를 실행한다. Role 생성 직후 psql의 비밀번호 입력 명령을 사용하면 비밀번호를 SQL 이력에 직접 기록하지 않는다. 기존 Role 비밀번호나 DB 소유권은 초기화하지 않는다.

```sql
CREATE ROLE adcheck_service WITH LOGIN;
\password adcheck_service
CREATE DATABASE adcheck OWNER adcheck_service;
```

애플리케이션 설정은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 읽는다. `.env` 자동 로더는 없다. `.env` 파일을 만들기만 해서는 적용되지 않는다. zsh에서 비밀번호를 화면/명령 이력에 남기지 않고 설정하는 예시:

```sh
export DB_URL=jdbc:postgresql://localhost:5432/adcheck
export DB_USERNAME=adcheck_service
read -rs 'DB_PASSWORD?로컬 DB 비밀번호: '; echo
export DB_PASSWORD
cd backend
bash gradlew bootRun
```

기본 포트는 8080이다. 포트 충돌 시 `bash gradlew bootRun --args='--server.port=18080'`을 사용한다. 기존 Spring Boot Flyway 설정으로 V1 migration 실행과 Hibernate `validate` 성공을 확인한 뒤 애플리케이션을 종료한다. 수동 테이블 생성이나 migration 수정은 하지 않는다.

저장소 루트에서 적재한다:

```sh
psql -X -h localhost -U adcheck_service -d adcheck -c '\dt'
psql -X -h localhost -U adcheck_service -d adcheck \
  -v ON_ERROR_STOP=1 -f scripts/load_rule_reference_data.sql
```

이 스크립트는 단일 트랜잭션 안에서 canonical ingredient/source/rule 행을 upsert하고, canonical Rule의 `rule_ingredients`와 `rule_sources`를 삭제 후 재구성한다. unrelated Rule 집합은 남긴다. 기존 canonical 행을 로컬에서 수정했다면 그 값은 덮어써질 수 있으므로 실행 전에 확인해야 한다. 이 구현은 스크립트와 CSV를 수정하지 않았다.

정상 건수는 ingredient_master **607**, reference_sources **15**, rules **71**, rule_ingredients **41**, rule_sources **97**, COMMON **30**, INGREDIENT_SPECIFIC **41**이다. 출처 없는 Rule, 원료 없는 원료별 Rule, 출력된 5개 FK orphan 결과가 모두 0이어야 한다. 기존 비canonical 행이 있다면 전체 건수는 달라질 수 있다. CSV 행 수만 확인한 것은 DB 적재 검증이 아니다.

## 서비스 입력과 Backend 통합 지점

기존 코드에 독립 `Claim`/`RiskSignalCandidates` DTO는 없다. `ClaimAnalysisResult`는 기존 분석기의 Finding 반환 타입이므로 재사용하지 않았다. Rule Engine 경계에 한 Claim과 후보 ruleCode 집합을 정의했고, Official Function은 기존 `OfficialFunctionReadModel`을 재사용했다.

```java
var request = new RuleAnalysisRequest(
    new RuleAnalysisRequest.Claim(
        "claim-42",
        "이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다.",
        RuleAnalysisRequest.Context.PRODUCT_COPY,
        "page-evidence-7#copy: 전체 문장과 주변 문맥을 확인한 근거 위치"
    ),
    Set.of("C07_ABSOLUTE_EFFECT"), // 후보일 뿐 최종 판단이 아님
    Set.of(),                    // Backend가 확정한 IngredientMaster ID만 전달
    null                         // 공식 기능성이 미확인되면 null 가능
);
RuleAnalysisResult result = ruleAnalysisService.analyze(request);
```

서비스 호출 전에 Backend가 제품/원료를 확정하고, `OfficialFunctionQueryService.findAllByIngredientMasterIds(...)` 결과를 다음과 같이 전달할 수 있다:

```java
var functions = officialFunctionQueryService.findAllByIngredientMasterIds(confirmedIds);
var official = new RuleAnalysisRequest.OfficialFunctions(
    functions,
    productApplicabilityVerified, // 함량·일일섭취량·제품별 인정문구 적용을 별도로 확인했는가
    allMainIngredientsCovered     // 다른 주원료까지 빠짐없이 확인했는가
);
var request = new RuleAnalysisRequest(claim, candidateRuleCodes, Set.copyOf(confirmedIds), official);
var result = ruleAnalysisService.analyze(request);
```

두 boolean은 공식 원문 조회 성공만으로 `true`로 설정하면 안 된다. 현재 공식 기능성 조회 계층은 제품별 적용 조건 검증을 제공하지 않는다. 해당 확인은 Backend 통합 담당자의 별도 책임이며, 미확인 기본값은 `false`다. canonical 세 CSV는 제품/기능성 원문 테이블을 적재하지 않으므로 그것만으로 C05 비교 입력이 완성되지 않는다.

`Claim.context`는 후보 신호와 독립적으로 주변 광고·인용·제품 귀속을 확인한 값이다. `PRODUCT_COPY`는 추출 조각이 아닌 **해당 Claim의 전체 독립 제품 문장**을 확인했을 때만 사용한다. `NON_PRODUCT_INFORMATION`은 제품 효과에 연결되지 않는 독립 정보임을 확인한 경우다. 모르면 `UNKNOWN`을 사용하며, 근거 위치/설명인 `contextEvidence`가 없으면 자동 판단하지 않는다. 엔진은 이 확인을 직접 수행하거나 증빙의 진위를 검증하지 않는다.

null 후보·원료 집합은 빈 집합, null 공식 기능성은 미확인으로 취급한다. Claim ID 누락·0 이하 원료 ID·null 집합 원소 등 호출 계약 오류는 예외다. Claim 원문/문맥/기능성 부족은 `REVIEW_REQUIRED`다. 원료 ID의 실재·식별 정확성은 Backend 책임이며, 엔진은 이름 추정이나 fuzzy matching을 하지 않는다.

## 선택·조회·출력

- `RuleSelector`: COMMON 조회 + `RuleIngredient.ingredientMaster.id` 관계로 INGREDIENT_SPECIFIC 조회. Rule ID로 중복 제거하고 ruleCode 순으로 반환한다.
- `RuleEvaluatorRegistry`: evaluator의 실제 canonical ruleCode 등록. 중복 등록은 시작 실패, 미지원 Rule은 `REVIEW_REQUIRED`.
- `CommonRuleEvaluator`: 아래의 한정된 문장 평가. 검토한 0.1 버전·scope·application_conditions·exceptions·required_evidence와 DB가 다르면 `RULE_DEFINITION_CHANGED`.
- `RuleSourceResolver`: `RuleSource → ReferenceSource` fetch join 일괄 조회. COMMON만 있으면 2회, 원료가 있으면 3회 SELECT로 처리한다.
- `RuleAnalysisService`: 전체 조회/DTO 변환을 `@Transactional(readOnly=true)` 안에서 수행한다. JPA 엔티티/lazy proxy는 반환하지 않는다. 결과 저장은 하지 않는다.

결과는 Claim ID, 모든 선택 Rule의 ID/code/version/scope/category/severity/reviewStatus, candidatePresent, status/reasonCode/reason, 출처 목록과 진단 목록을 포함한다. 후보가 없는 Rule도 평가하며 후보가 없는 사실을 NOT_MATCHED의 근거로 사용하지 않는다.

위 입력의 결과 예시(전체 30개 중 C07 결과를 발췌한 설명):

```text
claimId: claim-42
ruleCode: C07_ABSOLUTE_EFFECT
ruleVersion: 0.1
scopeType: COMMON
judgmentCategory: ABSOLUTE_EFFECT
severity: HIGH
reviewStatus: DRAFT
candidatePresent: true
evaluation.status: MATCHED
evaluation.reasonCode: SUPPORTED_CONDITION_CONFIRMED
sources[].sourceId: LAW-01, REVIEW-02, REVIEW-03  (DB 연결 조회)
match.diagnostics: [DRAFT_RULE]
result.diagnostics: [INGREDIENT_SPECIFIC_NOT_EVALUATED]
```

출처는 `referenceSourceId`, `sourceId`, `title`, `sourceType`, `issuer`, `sourceUrl`, `documentVersion`, `verificationStatus`, `section`, `printedPage`, `pdfPage`를 DB 그대로 보존한다. null URL이나 페이지를 생성하지 않고, 페이지 범위 문자열을 정수로 변환하지 않는다. 출처가 없으면 `sources=[]`, 해당 Rule에 `SOURCE_MISSING`을 명시한다. 평가 status와 출처 완전성은 독립이므로 소비자는 두 필드를 함께 확인해야 한다.

확정 원료가 없으면 COMMON 평가를 계속하고 `INGREDIENT_SPECIFIC_NOT_EVALUATED`를 반환한다. 원료별 Rule은 현재 모두 미지원 평가로 반환된다. canonical Rule은 현재 전부 DRAFT이며 `DRAFT_RULE`과 DB reviewStatus를 노출한다. MATCHED는 지원 조건 충족이라는 엔진 결과이며 최종 법적 위반이나 전체 광고 적합 여부를 의미하지 않는다.

## 지원 Rule과 정확한 자동 평가 범위

`candidate_examples`는 판정 기준으로 사용하지 않는다. 모든 패턴은 공백 정규화 후 **전체 문장** 일치만 허용한다. C07/C24는 종결 `.` 또는 `!` 한 개를 선택적으로 허용한다. 접두/접미 문구, 복합문장, 인용부호, 다른 표현은 검토 대상이다.

| Rule | MATCHED | NOT_MATCHED | 자동 판단하지 않는 범위 |
| --- | --- | --- | --- |
| C07_ABSOLUTE_EFFECT | `이 제품을 섭취하면 누구나 {피로 개선/체지방 감소/기억력 개선} 효과를 {100%/반드시} 얻습니다` | `이 제품은 {동일 효과} 효과를 보장하지 않습니다`; `원료 함량은 100%입니다`; `영양성분 기준치는 100%입니다` | 암시, 배송·복합 수식, 다른 효과·동의어·개인차 문맥 |
| C24_OVERCONSUMPTION | `{균형 잡힌 식사 대신/매일 식사 대신} 이 제품만 {드세요/섭취하세요}` | `이 제품은 균형 잡힌 식사를 대체할 수 없습니다`; `이 제품의 1일 섭취량에는 {비타민 C가/칼슘이/아연이} {숫자} mg 들어 있습니다` | 권장량 초과, 잘못된 식습관 유지, 다른 대체 문구, 실제 함량 진위 |
| C05_FUNCTION_EXCEED | 현재 없음 | 제품 적용·모든 주원료 확인이 완료되고, 전체 Claim이 해당 원료 중 하나의 공식 기능성 원문과 공백 정규화 후 정확히 일치 | 표현이 다를 때 대상·작용·결과·조건 의미 비교; 더 강하거나 다른 효능 자동 판정 |

C07은 건강 효과 수식과 개인차 없는 확정 조건을 함께 확인한다. 함량/기준치 수식 및 명시적 부정은 조건 불충족/예외로 처리한다. C24는 균형식·매일 식사를 제품만으로 대체시키는 권유만 판단하고, 단순 영양성분 설명과 식사 대체 부정을 분리한다. 두 Rule의 canonical required_evidence는 null이지만, 이것이 주변 문맥 확인을 생략해도 된다는 뜻은 아니다.

C05는 모든 전달 원료 ID의 비어 있지 않은 공식 원문과 제품 적용 확인이 필요하며, 다른 ID에 속한 공식 기능성을 쓰지 않는다. 불일치는 범위 초과의 증명이 아니므로 REVIEW_REQUIRED다. C06 공식 기능성 Rule은 원료/함량/섭취량/제품별 조건을 종합 평가할 별도 evaluator가 없으므로 미지원이다.

세 Rule 공통으로 제품 효과에 귀속되지 않는 독립 정보임이 확인되면 해당 Claim에만 `EXCEPTION_CONFIRMED`를 반환한다. 이 문맥 확인 자체의 자동화, 자연어 조건 전체 해석, 이미지/레이아웃 평가는 범위 밖이다.

## REVIEW_REQUIRED와 장애 구분

| reasonCode | 의미 |
| --- | --- |
| UNSUPPORTED_RULE | 등록 evaluator 없음. 미지원 COMMON/원료별 Rule 포함 |
| RULE_DEFINITION_CHANGED | 검토한 canonical 평가 정의와 DB 정의 불일치 |
| MISSING_CLAIM_TEXT | 주장 원문 없음 |
| CONTEXT_UNVERIFIED | 주변 문맥 또는 확인 근거 없음 |
| OUTSIDE_SUPPORTED_LANGUAGE | 전체 문장이 지원 grammar 밖이라 조건/예외 판정 불가 |
| OFFICIAL_FUNCTION_DATA_INCOMPLETE | 확정 원료, 공식 원문, 모든 주원료 또는 제품 적용 확인 부족 |
| SEMANTIC_COMPARISON_REQUIRED | 공식 문구와 다르며 의미 비교 필요 |

DB 접속 실패와 프로그래밍 오류는 호출자에게 예외로 전파한다. 일괄 catch하여 REVIEW_REQUIRED로 숨기지 않는다. `NOT_MATCHED`도 광고 전체가 정상이라는 의미가 아니라 해당 Claim/Rule 조건이 불충족이거나 예외가 확인되었다는 의미다.

## 테스트

기존 및 신규 단위/H2 테스트:

```sh
cd backend
bash gradlew test --no-daemon
```

PostgreSQL 테스트는 기본 skip이며, Flyway와 canonical 적재가 끝난 DB에 대해서만 명시적으로 실행한다:

```sh
export ADCHECK_POSTGRES_TEST=true
bash gradlew test --tests 'com.adcheck.rule.service.RuleEnginePostgresTest' --rerun-tasks --no-daemon
unset ADCHECK_POSTGRES_TEST
```

이 테스트는 PostgreSQL임을 확인하고 연결 초기화에서 `default_transaction_read_only=on`, 테스트 트랜잭션 readOnly, Flyway off, Hibernate validate를 강제한다. DDL/DML/CSV 적재를 수행하지 않는다. H2 관계 테스트는 별도 메모리 DB와 rollback 트랜잭션에 synthetic fixture를 삽입한다. PostgreSQL 테스트에 `test` 프로필을 지정하지 않는다.

검증 항목은 COMMON/연결 원료 선택, 타 원료 제외, 복수 연결 중복 제거, 원료 없음, 대표 Rule MATCHED/NOT_MATCHED/예외/근거 부족, 미지원, 전체 출처 metadata와 null 값, 출처 누락, 후보만으로 확정 금지, 정의 변경 방어, 장애 전파 및 3회 일괄 SELECT다.

## 실제 실행 결과

2026-09-11 로컬 실행 기록:

- 원격 fetch 후 `feature/rule-engine` 시작점과 `origin/integration/rule-engine-base`가 같은 `8eabedf`임을 확인했다. 지정 worktree는 처음에 clean이었다.
- Java 21.0.12.1, PostgreSQL/psql 17.11 설치 및 Gradle Wrapper 9.7.1 실행을 확인했다.
- 기존 테스트 38개, 신규 단위 34개, 별도 H2 관계/일괄 조회 4개로 **76개 통과, 실패 0개**. 기본 실행에서 PostgreSQL 전용 4개는 opt-in 미설정으로 skip했다.
- 이후 `ADCHECK_POSTGRES_TEST=true`로 실제 PostgreSQL 전용 테스트를 별도 실행해 **4개 통과, 실패/skip 0개**를 확인했다. 두 실행을 합해 80개 테스트를 검증했다.
- 최초 H2 관계 테스트의 fixture가 잘못된 IngredientCategory 값을 사용해 실패했으며, 테스트 fixture를 기존 `FUNCTIONAL_INGREDIENT` enum 값으로 수정했다. 기존 코드/schema 변경 없이 재실행에 통과했다.
- canonical CSV를 읽어서 기준 건수를 확인한 후, 실제 DB에서도 별도로 적재·검증했다. 두 검증을 구분했다.
- 기존 DB/Role이 없는 것을 확인한 뒤 사용자 로컬 비밀번호로 `adcheck_service` Role과 `adcheck` DB를 생성했다. 기존 인증 설정은 변경하지 않았다.
- 기존 `bootRun`을 18080 포트에서 실행해 Flyway V1 `create adcheck schema`의 success=true, 총 15개 테이블(Flyway 이력 포함), Hibernate validate 및 애플리케이션 시작 성공을 확인했다. 임시 애플리케이션은 검증 후 정상 종료했다.
- 저장소 루트에서 수정하지 않은 `scripts/load_rule_reference_data.sql`을 실행해 COMMIT 성공을 확인했다. 실제 DB 건수: ingredient_master **607**, reference_sources **15**, rules **71**, rule_ingredients **41**, rule_sources **97**, COMMON **30**, INGREDIENT_SPECIFIC **41**.
- `rule_without_source` **0건**, `ingredient_specific_rule_without_ingredient` **0건**, 적재 스크립트와 PostgreSQL 테스트의 5개 FK orphan 검사 **모두 0건**.
- 실제 DB의 C07/C24 MATCHED, C24 부정문 NOT_MATCHED, C05 근거 부족 REVIEW_REQUIRED, 원료 연결 선택과 미지원 원료별 Rule REVIEW_REQUIRED, COMMON 30개 및 연결 출처의 모든 반환 metadata 일치를 검증했다. PostgreSQL 테스트는 SELECT-only/read-only 연결에서 실행했다.
- DB 장애/프로그래밍 오류 전파, 출처 null/누락, 복수 연결 중복 제거 및 3회 일괄 SELECT는 단위/H2에서 검증했다. 실제 PostgreSQL 데이터에는 없는 출처 누락 fixture를 DB에 삽입하지 않았다.
- 비밀번호는 저장소 밖의 로컬 입력 파일과 프로세스 환경에만 사용했고 코드/문서/커밋에 포함하지 않았다.

## 남은 한계

지원 grammar는 의도적으로 좁다. C05는 정확한 일치의 NOT_MATCHED 경로만 지원하며 의미 비교에 의한 MATCHED는 없다. 그 외 COMMON 27개와 원료별 41개는 REVIEW_REQUIRED다. 원료와 문맥 및 제품 적용 사실은 호출자가 확인해야 한다. 결과는 영속화하지 않으며 Claim extraction, RAG/LLM, Product Identification, 전체 Backend 통합, 프론트엔드 변경은 포함하지 않는다. 법령·공식 출처의 최신성은 DB metadata를 전달할 뿐 별도로 검증하지 않는다.
