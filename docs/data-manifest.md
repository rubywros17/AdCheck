# AdCheck Canonical Data Manifest

- Common Data Contract: v0.2
- DB Schema: v0.2
- Product/Ingredient Dataset: v0.2
- Rule Dataset: v0.1
- Status: FINAL
- Updated: 2026-09-10

## 문서 목적

Backend와 AI 담당자가 서로 다른 로컬 PostgreSQL을 사용하더라도 동일한 Schema와 canonical dataset을 사용하기 위한 팀 공통 기준이다. Manifest 자체에 별도 v0.2 버전을 부여하지 않으며, v0.2는 Common Data Contract, DB Schema 및 Product/Ingredient Dataset의 버전을 뜻한다.

## Current Canonical Dataset

| Domain | Filename | Expected Rows | Status | Purpose |
|---|---|---:|---|---|
| Product | `products_v0.2.csv` | 45,970 | FINAL | 공식 건강기능식품 제품 데이터. 공식 식별값은 `productReportNo` |
| Ingredient | `ingredient_master_v0.2.csv` | 607 | FINAL | 내부 canonical IngredientMaster. MVP 10개 원료에 `ingredientCode` 존재 |
| Ingredient | `ingredient_synonyms_v0.2.csv` | 3,302 | FINAL | 원료 `rawText`를 IngredientMaster에 연결하는 매핑 사전 |
| Ingredient | `functional_ingredients_v0.2.csv` | 773 | FINAL | 개별인정형 원료의 공식 기능성 데이터 |
| Ingredient | `notified_ingredients_v0.2.csv` | 96 | FINAL | 고시형 원료의 공식 기능성 데이터 |
| Product–Ingredient Match | `product_ingredient_matches_v0.2.csv` | 176,447 | FINAL | Product와 IngredientMaster 사이의 사전 계산 매핑 |
| Rule | `rules_v0.1.csv` | 71 | FINAL | 현재 canonical Rule dataset. COMMON 30건, INGREDIENT_SPECIFIC 41건 |
| Reference | `reference_sources_v0.1.csv` | 15 | FINAL | Rule의 공식 근거·법률·가이드 데이터 |
| Ad Case | `ad_cases_v0.1.csv` | 38 | FINAL | 광고 사례 데이터. GOOD 10건, BAD 10건, DISCUSSION 18건 |

`rules_v0.2.csv`는 존재하지 않는다. `rules_v0.1_summary.yaml`은 검증 참고자료이며 실행 또는 DB 적재 대상이 아니다.

## Version 원칙

- DB Schema 버전과 개별 데이터셋 버전은 독립적으로 관리한다.
- 현재 DB Schema는 v0.2이고, 현재 canonical Rule dataset은 `rules_v0.1.csv`이다. 두 버전 번호가 다른 것은 정상이다.
- 파일명에 명시된 버전을 기준으로 사용하며 존재하지 않는 버전을 임의로 가정하지 않는다.

## Local DB 원칙

- Backend와 AI 담당자는 각각 별도의 로컬 PostgreSQL을 사용할 수 있다.
- 로컬 DB가 달라도 Schema 버전과 canonical dataset은 동일해야 한다.
- Unit Test에는 목적에 맞는 작은 fixture를 사용할 수 있다.
- Integration/E2E 검증에는 이 문서에 명시된 canonical dataset을 사용한다.

## ProductIngredientMatch 최종 상태

- Canonical file: `data/product_ingredient_matches_v0.2.csv`
- Rows: 176,447
- Status: FINAL
- SHA-256: `88584881387dec139433898e55b6c8ba57301c42df5186869b8ef481c1656010`

### 검증 이력

- `ingredient_synonyms`와 기존 precomputed match 결과 간 version skew 발견
- 전체 176,447건 재계산
- UNMATCHED → MATCHED 13건
- MATCHED → UNMATCHED 0건
- 기존 MATCHED의 IngredientMaster 변경 0건
- row 추가/누락 0건
- invalid Product/IngredientMaster FK 0건
- 기존/new `(productReportNo, rawText)` multiset 동일
- 최종 Validation status FINAL

검증 근거는 `data/validation/product_ingredient_matches_v0.2_rebuilt.csv`, `product_ingredient_matches_v0.2_diff.csv`, `product_ingredient_matches_v0.2_validation.md`에 보존한다.

## Ambiguous Normalized Key 정책

알려진 ambiguous normalized key는 다음과 같다.

- 홍삼농축액
- MSM
- 자일리톨
- 레시틴

매칭 정책은 다음과 같다.

1. recognitionNo 기반 매칭을 우선한다.
2. exact synonym 매칭을 우선한다.
3. `NORMALIZED_NAME`은 하나의 IngredientMaster로 유일하게 확정될 때만 MATCHED 처리한다.
4. 여러 IngredientMaster 후보가 나오는 normalized key는 자동 MATCHED 인덱스에서 제외한다.
5. 상위 매칭 단계에서도 확정할 수 없으면 강제로 매칭하지 않고 REVIEW_REQUIRED 처리한다.

Ambiguous key가 존재한다는 사실 자체는 데이터 오류가 아니다. 해당 key를 offline/runtime 자동 `NORMALIZED_NAME` MATCHED에 사용하지 않는 것이 canonical 정책이다.

## 데이터 적재 후 EXPECTED COUNT SQL

```sql
SELECT 'products' AS dataset, COUNT(*) AS actual_rows, 45970 AS expected_rows FROM products
UNION ALL
SELECT 'ingredient_master', COUNT(*), 607 FROM ingredient_master
UNION ALL
SELECT 'ingredient_synonyms', COUNT(*), 3302 FROM ingredient_synonyms
UNION ALL
SELECT 'functional_ingredients', COUNT(*), 773 FROM functional_ingredients
UNION ALL
SELECT 'notified_ingredients', COUNT(*), 96 FROM notified_ingredients
UNION ALL
SELECT 'product_ingredient_matches', COUNT(*), 176447 FROM product_ingredient_matches
UNION ALL
SELECT 'rules', COUNT(*), 71 FROM rules
UNION ALL
SELECT 'reference_sources', COUNT(*), 15 FROM reference_sources
UNION ALL
SELECT 'ad_cases', COUNT(*), 38 FROM ad_cases;

SELECT scope_type, COUNT(*) AS actual_rows
FROM rules
GROUP BY scope_type
ORDER BY scope_type;

SELECT case_type, COUNT(*) AS actual_rows
FROM ad_cases
GROUP BY case_type
ORDER BY case_type;
```

Rule 분포 기대값은 `COMMON = 30`, `INGREDIENT_SPECIFIC = 41`이며, 광고 사례 분포 기대값은 `GOOD = 10`, `BAD = 10`, `DISCUSSION = 18`이다.

## 변경 관리 원칙

- Canonical dataset이 변경되면 이 manifest도 같은 변경 단위에서 갱신한다.
- Canonical 파일을 개인적으로 수정한 사본을 공통 데이터처럼 사용하지 않는다.
- 새 데이터 버전을 만들 때 변경 이유와 생성일을 기록한다.
- Offline precompute와 runtime fallback은 동일한 matching rule과 정규화 기준을 사용한다.
