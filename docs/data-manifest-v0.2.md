# AdCheck Data Manifest v0.2

## 1. 문서 목적

Backend와 AI 담당자가 서로 다른 로컬 PostgreSQL을 사용하더라도 동일한 DB Schema와 canonical dataset을 사용하기 위한 팀 공통 기준이다. 이 문서는 데이터 파일의 기준 버전, 예상 건수 및 검증 방법을 정의한다.

## 2. Current Canonical Dataset

| Domain | Filename | Expected Rows | Status | Purpose |
|---|---|---:|---|---|
| Product | `products_v0.2.csv` | 45,970 | CANONICAL | 공식 건강기능식품 제품 데이터. 공식 식별값은 `productReportNo` |
| Ingredient | `ingredient_master_v0.2.csv` | 607 | CANONICAL | 내부 canonical IngredientMaster. MVP 10개 원료에 `ingredientCode` 존재 |
| Ingredient | `ingredient_synonyms_v0.2.csv` | 3,302 | CANONICAL | 원료 `rawText`를 IngredientMaster에 연결하는 매핑 사전 |
| Ingredient | `functional_ingredients_v0.2.csv` | 773 | CANONICAL | 개별인정형 원료의 공식 기능성 데이터 |
| Ingredient | `notified_ingredients_v0.2.csv` | 96 | CANONICAL | 고시형 원료의 공식 기능성 데이터 |
| Product–Ingredient Match | `product_ingredient_matches_v0.2.csv` | 176,447 | REVIEW_REQUIRED | Product와 IngredientMaster 사이의 사전 계산 매핑 |
| Rule | `rules_v0.1.csv` | 71 | CANONICAL | DB Schema v0.2의 Rule 적재 기준. COMMON 30건, INGREDIENT_SPECIFIC 41건 |
| Reference | `reference_sources_v0.1.csv` | 15 | CANONICAL | Rule의 공식 근거·법률·가이드 데이터 |
| Ad Case | `ad_cases_v0.1.csv` | 38 | CANONICAL | 실제 광고 사례. GOOD 10건, BAD 10건, DISCUSSION 18건 |

`rules_v0.1_summary.yaml`은 검증 요약 파일이며 실행 또는 DB 적재 대상이 아니다. 현재 `rules_v0.2.csv`는 존재하지 않는다.

## 3. Version 원칙

- DB Schema 버전과 데이터 파일 버전은 반드시 같을 필요가 없다.
- 현재 DB Schema 기준은 `v0.2`이다.
- 현재 Rule 적재 기준은 `rules_v0.1.csv`이다.
- 파일명에 명시된 버전을 기준으로 사용하며, 존재하지 않는 버전을 임의로 가정하지 않는다.

## 4. Local DB 원칙

- Backend와 AI 담당자는 각각 별도의 로컬 PostgreSQL을 사용할 수 있다.
- 로컬 DB가 달라도 Schema 버전과 canonical dataset 버전은 동일해야 한다.
- Unit Test에는 목적에 맞는 작은 fixture를 사용할 수 있다.
- Integration/E2E 검증에는 이 문서에 명시된 canonical dataset을 사용한다.

## 5. ProductIngredientMatch 주의사항

`product_ingredient_matches_v0.2.csv`의 현재 상태는 `REVIEW_REQUIRED`이다. `ingredient_synonyms_v0.2.csv`와 일부 매핑 결과 사이에 버전 시점 불일치가 확인되었으므로 FINAL 또는 CANONICAL로 취급하지 않는다.

최신 synonym 및 matching rule을 기준으로 전체 재검증하거나 재생성한 후 canonical 여부를 확정한다. Offline precompute와 runtime fallback은 반드시 동일한 matching rule과 정규화 기준을 사용해야 한다.

## 6. 데이터 적재 후 EXPECTED COUNT SQL

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

## 7. 변경 관리 원칙

- canonical dataset이 변경되면 이 manifest를 같은 변경 단위에서 함께 갱신한다.
- canonical 파일을 개인적으로 수정한 사본을 공통 데이터처럼 사용하지 않는다.
- 새 데이터 버전을 만들 때 변경 이유와 생성일을 기록한다.
- 재현 가능한 생성·정제 기준이 확정되기 전에는 기존 canonical 파일을 덮어쓰지 않는다.
