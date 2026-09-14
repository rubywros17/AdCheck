\set ON_ERROR_STOP on

-- Run this script from the repository root so the relative CSV paths below resolve.
BEGIN;

CREATE TEMP TABLE staging_ingredient_master (
    id text,
    standard_name text,
    ingredient_code text,
    ingredient_category text,
    support_status text
) ON COMMIT DROP;

CREATE TEMP TABLE staging_reference_sources (
    source_id text,
    title text,
    source_type text,
    issuer text,
    source_url text,
    local_source_path text,
    parent_source_id text,
    document_version text,
    issued_at text,
    effective_from text,
    effective_to text,
    retrieved_at text,
    verification_status text,
    section text,
    printed_page text,
    pdf_page text,
    notes text
) ON COMMIT DROP;

CREATE TEMP TABLE staging_rules (
    rule_code text,
    scope_type text,
    ingredient_codes text,
    expression_type text,
    candidate_examples text,
    judgment_category text,
    application_conditions text,
    exceptions text,
    required_evidence text,
    severity text,
    source_ids text,
    case_ids text,
    rule_version text,
    review_status text
) ON COMMIT DROP;

\copy staging_ingredient_master FROM 'backend/src/main/resources/db/migration/data/ingredient_master_v0.2.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
\copy staging_reference_sources FROM 'backend/src/main/resources/db/migration/data/reference_sources_v0.1.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
\copy staging_rules FROM 'backend/src/main/resources/db/migration/data/rules_v0.1.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

-- Parse JSON with a row/field-specific message instead of exposing only a cast error.
CREATE FUNCTION pg_temp.parse_canonical_jsonb(
    raw_value text,
    field_name text,
    row_key text
) RETURNS jsonb
LANGUAGE plpgsql
AS $function$
DECLARE
    parsed_value jsonb;
BEGIN
    IF raw_value IS NULL OR btrim(raw_value) = '' THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format(
                'Missing JSON value in rules.%s for ruleCode %s',
                field_name,
                row_key
            );
    END IF;

    BEGIN
        parsed_value := raw_value::jsonb;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format(
                'Malformed JSON in rules.%s for ruleCode %s',
                field_name,
                row_key
            ),
            DETAIL = SQLERRM;
    END;

    RETURN parsed_value;
END;
$function$;

-- Validate the complete canonical inputs before changing persistent tables.
DO $validation$
DECLARE
    invalid_values text;
BEGIN
    SELECT string_agg(format('%s (%s rows)', source_id, row_count), ', ' ORDER BY source_id)
      INTO invalid_values
      FROM (
          SELECT source_id, count(*) AS row_count
            FROM staging_reference_sources
           GROUP BY source_id
          HAVING count(*) > 1
      ) duplicates;

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate sourceId in reference_sources_v0.1.csv: %', invalid_values;
    END IF;

    SELECT string_agg(format('%s (%s rows)', rule_code, row_count), ', ' ORDER BY rule_code)
      INTO invalid_values
      FROM (
          SELECT rule_code, count(*) AS row_count
            FROM staging_rules
           GROUP BY rule_code
          HAVING count(*) > 1
      ) duplicates;

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate ruleCode in rules_v0.1.csv: %', invalid_values;
    END IF;

    -- Calling the parser for every row makes malformed JSON fail immediately.
    PERFORM pg_temp.parse_canonical_jsonb(ingredient_codes, 'ingredientCodes', rule_code)
      FROM staging_rules;
    PERFORM pg_temp.parse_canonical_jsonb(candidate_examples, 'candidateExamples', rule_code)
      FROM staging_rules;
    PERFORM pg_temp.parse_canonical_jsonb(source_ids, 'sourceIds', rule_code)
      FROM staging_rules;

    SELECT string_agg(rule_code, ', ' ORDER BY rule_code)
      INTO invalid_values
      FROM staging_rules
     WHERE jsonb_typeof(
               pg_temp.parse_canonical_jsonb(ingredient_codes, 'ingredientCodes', rule_code)
           ) IS DISTINCT FROM 'array';

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'rules.ingredientCodes must be a JSON array. ruleCode: %', invalid_values;
    END IF;

    SELECT string_agg(rule_code, ', ' ORDER BY rule_code)
      INTO invalid_values
      FROM staging_rules
     WHERE jsonb_typeof(
               pg_temp.parse_canonical_jsonb(candidate_examples, 'candidateExamples', rule_code)
           ) IS DISTINCT FROM 'array';

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'rules.candidateExamples must be a JSON array. ruleCode: %', invalid_values;
    END IF;

    SELECT string_agg(rule_code, ', ' ORDER BY rule_code)
      INTO invalid_values
      FROM staging_rules
     WHERE jsonb_typeof(
               pg_temp.parse_canonical_jsonb(source_ids, 'sourceIds', rule_code)
           ) IS DISTINCT FROM 'array';

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'rules.sourceIds must be a JSON array. ruleCode: %', invalid_values;
    END IF;

    SELECT string_agg(missing_code, ', ' ORDER BY missing_code)
      INTO invalid_values
      FROM (
          SELECT DISTINCT ingredient_code.value AS missing_code
            FROM staging_rules staged_rule
            CROSS JOIN LATERAL jsonb_array_elements_text(
                pg_temp.parse_canonical_jsonb(
                    staged_rule.ingredient_codes,
                    'ingredientCodes',
                    staged_rule.rule_code
                )
            ) AS ingredient_code(value)
            LEFT JOIN staging_ingredient_master staged_ingredient
              ON staged_ingredient.ingredient_code = ingredient_code.value
           WHERE staged_ingredient.id IS NULL
      ) missing;

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'Unknown ingredientCode referenced by rules_v0.1.csv: %', invalid_values;
    END IF;

    SELECT string_agg(missing_source_id, ', ' ORDER BY missing_source_id)
      INTO invalid_values
      FROM (
          SELECT DISTINCT source_id.value AS missing_source_id
            FROM staging_rules staged_rule
            CROSS JOIN LATERAL jsonb_array_elements_text(
                pg_temp.parse_canonical_jsonb(
                    staged_rule.source_ids,
                    'sourceIds',
                    staged_rule.rule_code
                )
            ) AS source_id(value)
            LEFT JOIN staging_reference_sources staged_source
              ON staged_source.source_id = source_id.value
           WHERE staged_source.source_id IS NULL
      ) missing;

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'Unknown sourceId referenced by rules_v0.1.csv: %', invalid_values;
    END IF;

    SELECT string_agg(
               format('%s -> %s', child.source_id, child.parent_source_id),
               ', ' ORDER BY child.source_id
           )
      INTO invalid_values
      FROM staging_reference_sources child
      LEFT JOIN staging_reference_sources parent
        ON parent.source_id = child.parent_source_id
     WHERE child.parent_source_id IS NOT NULL
       AND btrim(child.parent_source_id) <> ''
       AND parent.source_id IS NULL;

    IF invalid_values IS NOT NULL THEN
        RAISE EXCEPTION 'Unknown parentSourceId in reference_sources_v0.1.csv: %', invalid_values;
    END IF;
END;
$validation$;

INSERT INTO ingredient_master (
    id,
    standard_name,
    ingredient_code,
    ingredient_category,
    support_status
)
SELECT btrim(id)::bigint,
       standard_name,
       CASE WHEN ingredient_code IS NULL OR btrim(ingredient_code) = ''
            THEN NULL ELSE ingredient_code END,
       ingredient_category,
       support_status
  FROM staging_ingredient_master
ON CONFLICT (id) DO UPDATE
SET standard_name = EXCLUDED.standard_name,
    ingredient_code = EXCLUDED.ingredient_code,
    ingredient_category = EXCLUDED.ingredient_category,
    support_status = EXCLUDED.support_status;

-- Parent links are intentionally cleared during upsert and restored after every
-- canonical source is present.
INSERT INTO reference_sources (
    source_id,
    title,
    source_type,
    issuer,
    source_url,
    local_source_path,
    parent_source_id,
    document_version,
    issued_at_raw,
    effective_from_raw,
    effective_to_raw,
    retrieved_at_raw,
    verification_status,
    section,
    printed_page,
    pdf_page,
    notes
)
SELECT source_id,
       title,
       source_type,
       CASE WHEN issuer IS NULL OR btrim(issuer) = '' THEN NULL ELSE issuer END,
       CASE WHEN source_url IS NULL OR btrim(source_url) = '' THEN NULL ELSE source_url END,
       CASE WHEN local_source_path IS NULL OR btrim(local_source_path) = '' THEN NULL ELSE local_source_path END,
       NULL,
       CASE WHEN document_version IS NULL OR btrim(document_version) = '' THEN NULL ELSE document_version END,
       CASE WHEN issued_at IS NULL OR btrim(issued_at) = '' THEN NULL ELSE issued_at END,
       CASE WHEN effective_from IS NULL OR btrim(effective_from) = '' THEN NULL ELSE effective_from END,
       CASE WHEN effective_to IS NULL OR btrim(effective_to) = '' THEN NULL ELSE effective_to END,
       CASE WHEN retrieved_at IS NULL OR btrim(retrieved_at) = '' THEN NULL ELSE retrieved_at END,
       verification_status,
       CASE WHEN section IS NULL OR btrim(section) = '' THEN NULL ELSE section END,
       CASE WHEN printed_page IS NULL OR btrim(printed_page) = '' THEN NULL ELSE printed_page END,
       CASE WHEN pdf_page IS NULL OR btrim(pdf_page) = '' THEN NULL ELSE pdf_page END,
       CASE WHEN notes IS NULL OR btrim(notes) = '' THEN NULL ELSE notes END
  FROM staging_reference_sources
ON CONFLICT (source_id) DO UPDATE
SET title = EXCLUDED.title,
    source_type = EXCLUDED.source_type,
    issuer = EXCLUDED.issuer,
    source_url = EXCLUDED.source_url,
    local_source_path = EXCLUDED.local_source_path,
    parent_source_id = NULL,
    document_version = EXCLUDED.document_version,
    issued_at_raw = EXCLUDED.issued_at_raw,
    effective_from_raw = EXCLUDED.effective_from_raw,
    effective_to_raw = EXCLUDED.effective_to_raw,
    retrieved_at_raw = EXCLUDED.retrieved_at_raw,
    verification_status = EXCLUDED.verification_status,
    section = EXCLUDED.section,
    printed_page = EXCLUDED.printed_page,
    pdf_page = EXCLUDED.pdf_page,
    notes = EXCLUDED.notes;

UPDATE reference_sources child
   SET parent_source_id = parent.id
  FROM staging_reference_sources staged_child
  JOIN reference_sources parent
    ON parent.source_id = staged_child.parent_source_id
 WHERE child.source_id = staged_child.source_id
   AND staged_child.parent_source_id IS NOT NULL
   AND btrim(staged_child.parent_source_id) <> '';

INSERT INTO rules (
    rule_code,
    scope_type,
    expression_type,
    candidate_examples,
    judgment_category,
    application_conditions,
    exceptions,
    required_evidence,
    severity,
    rule_version,
    review_status
)
SELECT rule_code,
       scope_type,
       expression_type,
       pg_temp.parse_canonical_jsonb(candidate_examples, 'candidateExamples', rule_code),
       judgment_category,
       application_conditions,
       CASE WHEN exceptions IS NULL OR btrim(exceptions) = '' THEN NULL ELSE exceptions END,
       CASE WHEN required_evidence IS NULL
                  OR btrim(required_evidence) = ''
                  OR lower(btrim(required_evidence)) = 'null'
            THEN NULL ELSE required_evidence END,
       severity,
       rule_version,
       review_status
  FROM staging_rules
ON CONFLICT (rule_code) DO UPDATE
SET scope_type = EXCLUDED.scope_type,
    expression_type = EXCLUDED.expression_type,
    candidate_examples = EXCLUDED.candidate_examples,
    judgment_category = EXCLUDED.judgment_category,
    application_conditions = EXCLUDED.application_conditions,
    exceptions = EXCLUDED.exceptions,
    required_evidence = EXCLUDED.required_evidence,
    severity = EXCLUDED.severity,
    rule_version = EXCLUDED.rule_version,
    review_status = EXCLUDED.review_status;

-- Rebuild bridges only for rules present in this canonical file. This removes
-- stale relations while leaving unrelated rule sets untouched.
DELETE FROM rule_ingredients bridge
USING rules rule_row, staging_rules staged_rule
WHERE bridge.rule_id = rule_row.id
  AND rule_row.rule_code = staged_rule.rule_code;

INSERT INTO rule_ingredients (rule_id, ingredient_master_id)
SELECT DISTINCT rule_row.id, ingredient.id
  FROM staging_rules staged_rule
  JOIN rules rule_row
    ON rule_row.rule_code = staged_rule.rule_code
  CROSS JOIN LATERAL jsonb_array_elements_text(
      pg_temp.parse_canonical_jsonb(
          staged_rule.ingredient_codes,
          'ingredientCodes',
          staged_rule.rule_code
      )
  ) AS ingredient_code(value)
  JOIN ingredient_master ingredient
    ON ingredient.ingredient_code = ingredient_code.value;

DELETE FROM rule_sources bridge
USING rules rule_row, staging_rules staged_rule
WHERE bridge.rule_id = rule_row.id
  AND rule_row.rule_code = staged_rule.rule_code;

INSERT INTO rule_sources (rule_id, reference_source_id)
SELECT DISTINCT rule_row.id, source_row.id
  FROM staging_rules staged_rule
  JOIN rules rule_row
    ON rule_row.rule_code = staged_rule.rule_code
  CROSS JOIN LATERAL jsonb_array_elements_text(
      pg_temp.parse_canonical_jsonb(
          staged_rule.source_ids,
          'sourceIds',
          staged_rule.rule_code
      )
  ) AS source_id(value)
  JOIN reference_sources source_row
    ON source_row.source_id = source_id.value;

-- The canonical ingredient IDs are inserted explicitly, so align the identity
-- sequence only after every load step has succeeded.
SELECT setval(
    pg_get_serial_sequence('ingredient_master', 'id'),
    COALESCE((SELECT max(id) FROM ingredient_master), 1),
    EXISTS (SELECT 1 FROM ingredient_master)
);

-- Row totals.
SELECT count(*) AS ingredient_master_count FROM ingredient_master;
SELECT count(*) AS reference_sources_count FROM reference_sources;
SELECT count(*) AS rules_count FROM rules;
SELECT count(*) AS rule_ingredients_count FROM rule_ingredients;
SELECT count(*) AS rule_sources_count FROM rule_sources;

-- Canonical rule distributions.
SELECT rule_row.scope_type, count(*) AS rule_count
  FROM rules rule_row
  JOIN staging_rules staged_rule ON staged_rule.rule_code = rule_row.rule_code
 GROUP BY rule_row.scope_type
 ORDER BY rule_row.scope_type;

SELECT rule_row.severity, count(*) AS rule_count
  FROM rules rule_row
  JOIN staging_rules staged_rule ON staged_rule.rule_code = rule_row.rule_code
 GROUP BY rule_row.severity
 ORDER BY rule_row.severity;

SELECT rule_row.review_status, count(*) AS rule_count
  FROM rules rule_row
  JOIN staging_rules staged_rule ON staged_rule.rule_code = rule_row.rule_code
 GROUP BY rule_row.review_status
 ORDER BY rule_row.review_status;

-- These result sets should be empty for a complete canonical load.
SELECT rule_row.rule_code AS rule_without_source
  FROM rules rule_row
  JOIN staging_rules staged_rule ON staged_rule.rule_code = rule_row.rule_code
  LEFT JOIN rule_sources bridge ON bridge.rule_id = rule_row.id
 GROUP BY rule_row.id, rule_row.rule_code
HAVING count(bridge.id) = 0
 ORDER BY rule_row.rule_code;

SELECT rule_row.rule_code AS ingredient_specific_rule_without_ingredient
  FROM rules rule_row
  JOIN staging_rules staged_rule ON staged_rule.rule_code = rule_row.rule_code
  LEFT JOIN rule_ingredients bridge ON bridge.rule_id = rule_row.id
 WHERE rule_row.scope_type = 'INGREDIENT_SPECIFIC'
 GROUP BY rule_row.id, rule_row.rule_code
HAVING count(bridge.id) = 0
 ORDER BY rule_row.rule_code;

-- Every orphan_count should be zero. Foreign keys also enforce these relations.
SELECT 'reference_sources.parent_source_id' AS relationship, count(*) AS orphan_count
  FROM reference_sources child
  LEFT JOIN reference_sources parent ON parent.id = child.parent_source_id
 WHERE child.parent_source_id IS NOT NULL AND parent.id IS NULL
UNION ALL
SELECT 'rule_ingredients.rule_id', count(*)
  FROM rule_ingredients bridge
  LEFT JOIN rules rule_row ON rule_row.id = bridge.rule_id
 WHERE rule_row.id IS NULL
UNION ALL
SELECT 'rule_ingredients.ingredient_master_id', count(*)
  FROM rule_ingredients bridge
  LEFT JOIN ingredient_master ingredient ON ingredient.id = bridge.ingredient_master_id
 WHERE ingredient.id IS NULL
UNION ALL
SELECT 'rule_sources.rule_id', count(*)
  FROM rule_sources bridge
  LEFT JOIN rules rule_row ON rule_row.id = bridge.rule_id
 WHERE rule_row.id IS NULL
UNION ALL
SELECT 'rule_sources.reference_source_id', count(*)
  FROM rule_sources bridge
  LEFT JOIN reference_sources source_row ON source_row.id = bridge.reference_source_id
 WHERE source_row.id IS NULL
ORDER BY relationship;

COMMIT;
