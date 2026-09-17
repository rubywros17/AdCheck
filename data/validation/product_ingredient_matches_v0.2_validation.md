# Product Ingredient Matches v0.2 Validation

- Validation status: **FINAL**
- Source file was not overwritten; rebuilt and diff files are validation artifacts.

## Matching policy

Priority: PRODUCT_TYPE_RECOGNITION_NO -> RAW_RECOGNITION_NO -> SYNONYM_EXACT -> NORMALIZED_NAME -> MANUAL -> UNMATCHED.

- Product type recognition is considered only when rawText exactly equals the product's productType.
- Recognition numbers are normalized to YYYY-N; only one valid IngredientMaster candidate is accepted.
- Synonyms use exact string equality.
- Conservative name normalization uses Unicode NFKC, case folding, whitespace removal, and removal of a terminal (고시형) classification suffix.
- Normalized keys with multiple IngredientMaster candidates are separated into `ambiguousNormalizedKeys` and excluded from the automatic `NORMALIZED_NAME` index.
- If an input is not resolved by recognition number or exact synonym and only matches an ambiguous normalized key, it becomes `REVIEW_REQUIRED`.
- No fuzzy or partial matching is used. Multiple candidates are never forced to MATCHED.

## Results

| Metric | Count / Result |
|---|---:|
| Existing total rows | 176,447 |
| Rebuilt total rows | 176,447 |
| Existing MATCHED | 174,843 |
| New MATCHED | 174,856 |
| Existing REVIEW_REQUIRED | 0 |
| New REVIEW_REQUIRED | 0 |
| Existing UNMATCHED | 1,604 |
| New UNMATCHED | 1,591 |
| Changed rows | 983 |
| UNMATCHED -> MATCHED | 13 |
| MATCHED -> UNMATCHED/non-MATCHED | 0 |
| MASTER_CHANGED | 13 |
| METHOD_CHANGED | 983 |
| Missing productReportNo rows | 0 |
| Existing missing IngredientMaster FK rows | 0 |
| New missing IngredientMaster FK rows | 0 |
| Existing/new (productReportNo, rawText) multiset equal | YES |
| Existing MATCHED rows changed to another IngredientMaster | 0 |
| Unresolved input rows with multiple candidates | 0 |
| Unresolved multi-candidate rows force-matched | 0 |
| Ambiguous normalized keys excluded from auto-match index | YES |

## Ambiguous Normalized Keys

- Exact synonym keys mapping to multiple IngredientMasters: 0
- Normalized alias keys mapping to multiple IngredientMasters: 4
- All four keys are excluded from the offline/runtime automatic `NORMALIZED_NAME` matching index.

| Normalized key | Candidate IngredientMaster IDs |
|---|---|
| 홍삼농축액 | 586, 588 |
| MSM (`msm`) | 73, 389 |
| 자일리톨 | 90, 444 |
| 레시틴 | 90, 208 |

The existence of these ambiguous dictionary keys is not itself a NOT FINAL condition. No input row was force-matched through an ambiguous normalized key in this rebuild.

## Finality checks

The rebuilt dataset passes the revised canonical FINAL policy:

- No MATCHED row became UNMATCHED.
- No existing MATCHED row changed IngredientMaster.
- No row was added or removed, and the key multiset is identical.
- No missing Product or IngredientMaster foreign key was produced.
- No input rawText remained in a multiple-candidate conflict.
- No unresolved multi-candidate input was force-matched.
- All ambiguous normalized keys are excluded from the automatic `NORMALIZED_NAME` index.

## Input SHA-256

| File | SHA-256 |
|---|---|
| products_v0.2.csv | 3d2392a1e7b334ffee41927fdb663d7b9abb12dcc4b205b514b54b3907332a78 |
| ingredient_master_v0.2.csv | ff5d52899de2694993d100fadab8b718b9f6f25a7ce75b65172dc7c973700745 |
| ingredient_synonyms_v0.2.csv | d9df9c34aad3d5a11f7f38c15b3282c90fc21caada7c23fdfd9ef53751d800b6 |
| functional_ingredients_v0.2.csv | 8028f9d4e51e40fa39ddb82ccad60f604cafb95f1bcfbb8e421f404d8e67e3dc |
| notified_ingredients_v0.2.csv | be6a275f7209ce0bd885ffe1ade23edea405f6854c1af3bd470ee0dd6429b56b |
| product_ingredient_matches_v0.2.csv | 498604b5780ebd908b4a0329fa1f7a8429e8fe8a08018b280fa36ea080f46421 |
