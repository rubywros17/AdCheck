package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 팀원이 준 Rule 관련 참조데이터(rules_v0.1.csv, reference_sources_v0.1.csv,
 * ad_cases_v0.1.csv)를 1회성으로 Postgres에 적재한다. rules_v0.2라는 별도 파일은 없다 —
 * rules_v0.1이 계속 적재 기준(팀원 확인).
 *
 * <p>세 파일은 각각 독립 테이블(rules/reference_sources/ad_cases)로 적재되고, 서로의
 * 관계(원료코드/근거/사례)는 별도 junction 테이블(rule_ingredients/rule_sources/rule_cases/
 * ad_case_sources)로 풀어낸다 — rules/ad_cases 테이블 자체에는 그 관계를 나타내는 컬럼이
 * 없다.
 *
 * <p>rule_cases는 두 방향에서 관계가 표현된다 — rules.csv의 caseIds(JSON 배열)와
 * ad_cases.csv의 ruleCodes(콤마 목록, 일부 행은 마지막 코드 뒤에 "; 설명" 같은 부가 설명이
 * 붙어있어 세미콜론 앞부분만 취함)가 같은 관계를 반대 방향에서 적는다. 두 쪽 다 읽어서
 * (ruleId, adCaseId) 쌍을 합집합으로 모은 뒤 중복 제거하고 적재한다 — 한쪽에만 있는 관계를
 * 놓치지 않기 위함.
 *
 * <p>reference_sources/rules/rule_ingredients/rule_sources는 {@code scripts/load_rule_reference_data.sql}
 * 이 적재하는 테이블과 겹친다 — 이 마이그레이션이 적용된 이후로는 그 스크립트를 수동 실행하지
 * 않는다(스크립트 상단에 DEPRECATED 주석 추가됨).
 */
public class V5__LoadRuleData extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V5__LoadRuleData.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        List<ReferenceSourceRow> sources = readCsv("/db/migration/data/reference_sources_v0.1.csv", ReferenceSourceRow.class);
        insertReferenceSources(connection, sources);
        Map<String, Long> sourceIdByCode = loadIdMap(connection, "reference_sources", "source_id");
        resolveParentSources(connection, sources, sourceIdByCode);
        log.info("reference_sources {}건 적재 완료", sources.size());

        List<AdCaseRow> adCases = readCsv("/db/migration/data/ad_cases_v0.1.csv", AdCaseRow.class);
        insertAdCases(connection, adCases);
        Map<String, Long> adCaseIdByCode = loadIdMap(connection, "ad_cases", "case_id");
        insertAdCaseSources(connection, adCases, adCaseIdByCode, sourceIdByCode);
        log.info("ad_cases {}건 적재 완료", adCases.size());

        List<RuleRow> rules = readCsv("/db/migration/data/rules_v0.1.csv", RuleRow.class);
        insertRules(connection, rules);
        Map<String, Long> ruleIdByCode = loadIdMap(connection, "rules", "rule_code");
        Map<String, Long> ingredientMasterIdByCode = loadIngredientMasterIdByCode(connection);
        insertRuleIngredients(connection, rules, ruleIdByCode, ingredientMasterIdByCode);
        insertRuleSources(connection, rules, ruleIdByCode, sourceIdByCode);
        insertRuleCases(connection, rules, adCases, ruleIdByCode, adCaseIdByCode);
        log.info("rules {}건 적재 완료", rules.size());
    }

    private void insertReferenceSources(Connection connection, List<ReferenceSourceRow> rows) throws SQLException {
        batchInsert(connection,
                """
                INSERT INTO reference_sources
                    (source_id, title, source_type, issuer, source_url, local_source_path, document_version,
                     issued_at_raw, effective_from_raw, effective_to_raw, retrieved_at_raw, verification_status,
                     section, printed_page, pdf_page, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows,
                (ps, row) -> {
                    ps.setString(1, row.sourceId());
                    ps.setString(2, row.title());
                    ps.setString(3, row.sourceType());
                    setNullableString(ps, 4, row.issuer());
                    setNullableString(ps, 5, row.sourceUrl());
                    setNullableString(ps, 6, row.localSourcePath());
                    setNullableString(ps, 7, row.documentVersion());
                    setNullableString(ps, 8, row.issuedAt());
                    setNullableString(ps, 9, row.effectiveFrom());
                    setNullableString(ps, 10, row.effectiveTo());
                    setNullableString(ps, 11, row.retrievedAt());
                    ps.setString(12, row.verificationStatus());
                    setNullableString(ps, 13, row.section());
                    setNullableString(ps, 14, row.printedPage());
                    setNullableString(ps, 15, row.pdfPage());
                    setNullableString(ps, 16, row.notes());
                });
    }

    private void resolveParentSources(Connection connection, List<ReferenceSourceRow> rows, Map<String, Long> sourceIdByCode)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE reference_sources SET parent_source_id = ? WHERE source_id = ?")) {
            for (ReferenceSourceRow row : rows) {
                if (row.parentSourceId() == null || row.parentSourceId().isBlank()) {
                    continue;
                }
                Long parentId = sourceIdByCode.get(row.parentSourceId().strip());
                if (parentId == null) {
                    log.warn("parentSourceId={} 에 해당하는 reference_sources 행을 찾지 못함", row.parentSourceId());
                    continue;
                }
                ps.setLong(1, parentId);
                ps.setString(2, row.sourceId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertAdCases(Connection connection, List<AdCaseRow> rows) throws SQLException {
        batchInsert(connection,
                """
                INSERT INTO ad_cases
                    (case_id, case_type, ingredient_codes, other_main_ingredients, text_kind, quoted_text,
                     source_decision, source_reason_summary, printed_page, pdf_page, verification_status, notes)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows,
                (ps, row) -> {
                    ps.setString(1, row.caseId());
                    ps.setString(2, row.caseType());
                    setNullableString(ps, 3, toJsonArrayOrNull(row.ingredientCodes()));
                    setNullableString(ps, 4, row.otherMainIngredients());
                    setNullableString(ps, 5, row.textKind());
                    setNullableString(ps, 6, row.quotedText());
                    setNullableString(ps, 7, row.sourceDecision());
                    setNullableString(ps, 8, row.sourceReasonSummary());
                    setNullableString(ps, 9, row.printedPage());
                    setNullableString(ps, 10, row.pdfPage());
                    ps.setString(11, row.verificationStatus());
                    setNullableString(ps, 12, row.notes());
                });
    }

    private void insertAdCaseSources(Connection connection, List<AdCaseRow> rows,
                                      Map<String, Long> adCaseIdByCode, Map<String, Long> sourceIdByCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ad_case_sources (ad_case_id, reference_source_id) VALUES (?, ?)")) {
            for (AdCaseRow row : rows) {
                if (row.sourceId() == null || row.sourceId().isBlank()) {
                    continue;
                }
                Long adCaseId = adCaseIdByCode.get(row.caseId());
                Long sourceId = sourceIdByCode.get(row.sourceId().strip());
                if (adCaseId == null || sourceId == null) {
                    log.warn("ad_case_sources 해석 실패: caseId={}, sourceId={}", row.caseId(), row.sourceId());
                    continue;
                }
                ps.setLong(1, adCaseId);
                ps.setLong(2, sourceId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertRules(Connection connection, List<RuleRow> rows) throws SQLException {
        batchInsert(connection,
                """
                INSERT INTO rules
                    (rule_code, scope_type, expression_type, candidate_examples, judgment_category,
                     application_conditions, exceptions, required_evidence, severity, rule_version, review_status)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows,
                (ps, row) -> {
                    ps.setString(1, row.ruleCode());
                    ps.setString(2, row.scopeType());
                    ps.setString(3, row.expressionType());
                    ps.setString(4, row.candidateExamples());
                    ps.setString(5, row.judgmentCategory());
                    ps.setString(6, row.applicationConditions());
                    setNullableString(ps, 7, nullIfLiteralNull(row.exceptions()));
                    setNullableString(ps, 8, nullIfLiteralNull(row.requiredEvidence()));
                    ps.setString(9, row.severity());
                    ps.setString(10, row.ruleVersion());
                    ps.setString(11, row.reviewStatus());
                });
    }

    private void insertRuleIngredients(Connection connection, List<RuleRow> rules,
                                        Map<String, Long> ruleIdByCode, Map<String, Long> ingredientMasterIdByCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO rule_ingredients (rule_id, ingredient_master_id) VALUES (?, ?)")) {
            for (RuleRow rule : rules) {
                Long ruleId = ruleIdByCode.get(rule.ruleCode());
                for (String code : parseJsonStringArray(rule.ingredientCodes())) {
                    Long ingredientMasterId = ingredientMasterIdByCode.get(code);
                    if (ingredientMasterId == null) {
                        log.warn("rule_ingredients 해석 실패: ruleCode={}, ingredientCode={}", rule.ruleCode(), code);
                        continue;
                    }
                    ps.setLong(1, ruleId);
                    ps.setLong(2, ingredientMasterId);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void insertRuleSources(Connection connection, List<RuleRow> rules,
                                    Map<String, Long> ruleIdByCode, Map<String, Long> sourceIdByCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO rule_sources (rule_id, reference_source_id) VALUES (?, ?)")) {
            for (RuleRow rule : rules) {
                Long ruleId = ruleIdByCode.get(rule.ruleCode());
                for (String sourceCode : parseJsonStringArray(rule.sourceIds())) {
                    Long sourceId = sourceIdByCode.get(sourceCode);
                    if (sourceId == null) {
                        log.warn("rule_sources 해석 실패: ruleCode={}, sourceId={}", rule.ruleCode(), sourceCode);
                        continue;
                    }
                    ps.setLong(1, ruleId);
                    ps.setLong(2, sourceId);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /** rules.caseIds(정방향)와 ad_cases.ruleCodes(역방향) 양쪽을 합쳐서 중복 없이 적재한다. */
    private void insertRuleCases(Connection connection, List<RuleRow> rules, List<AdCaseRow> adCases,
                                  Map<String, Long> ruleIdByCode, Map<String, Long> adCaseIdByCode) throws SQLException {
        record RuleCasePair(long ruleId, long adCaseId) {
        }
        Set<RuleCasePair> pairs = new HashSet<>();

        for (RuleRow rule : rules) {
            Long ruleId = ruleIdByCode.get(rule.ruleCode());
            for (String caseCode : parseJsonStringArray(rule.caseIds())) {
                Long adCaseId = adCaseIdByCode.get(caseCode);
                if (ruleId == null || adCaseId == null) {
                    log.warn("rule_cases(정방향) 해석 실패: ruleCode={}, caseId={}", rule.ruleCode(), caseCode);
                    continue;
                }
                pairs.add(new RuleCasePair(ruleId, adCaseId));
            }
        }
        for (AdCaseRow adCase : adCases) {
            Long adCaseId = adCaseIdByCode.get(adCase.caseId());
            for (String ruleCode : parseRuleCodesList(adCase.ruleCodes())) {
                Long ruleId = ruleIdByCode.get(ruleCode);
                if (ruleId == null || adCaseId == null) {
                    log.warn("rule_cases(역방향) 해석 실패: caseId={}, ruleCode={}", adCase.caseId(), ruleCode);
                    continue;
                }
                pairs.add(new RuleCasePair(ruleId, adCaseId));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO rule_cases (rule_id, ad_case_id) VALUES (?, ?)")) {
            for (RuleCasePair pair : pairs) {
                ps.setLong(1, pair.ruleId());
                ps.setLong(2, pair.adCaseId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Map<String, Long> loadIdMap(Connection connection, String table, String codeColumn) throws SQLException {
        Map<String, Long> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, " + codeColumn + " FROM " + table)) {
            while (rs.next()) {
                map.put(rs.getString(2), rs.getLong(1));
            }
        }
        return map;
    }

    private static Map<String, Long> loadIngredientMasterIdByCode(Connection connection) throws SQLException {
        Map<String, Long> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, ingredient_code FROM ingredient_master WHERE ingredient_code IS NOT NULL")) {
            while (rs.next()) {
                map.put(rs.getString(2), rs.getLong(1));
            }
        }
        return map;
    }

    private List<String> parseJsonStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            log.warn("JSON 배열 파싱 실패: {}", json);
            return List.of();
        }
    }

    /**
     * ad_cases.csv의 ruleCodes는 콤마로 구분된 목록이지만, 일부 행("DISC-*" 계열)은 마지막
     * 코드 뒤에 세미콜론 + 부가 설명이 그대로 이어붙어 있다(예: "C01_DISEASE_PREVENTION;
     * 질병정보 구분 필요"). 세미콜론 앞부분만 규칙 코드로 취급한다.
     */
    private static List<String> parseRuleCodesList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String token : raw.split(",")) {
            String code = token.split(";", 2)[0].strip();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private static String toJsonArrayOrNull(String bareValue) {
        if (bareValue == null || bareValue.isBlank()) {
            return null;
        }
        return "[\"" + bareValue.strip().replace("\"", "\\\"") + "\"]";
    }

    private static String nullIfLiteralNull(String value) {
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("null")) {
            return null;
        }
        return value;
    }

    @FunctionalInterface
    private interface RowBinder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }

    private static <T> void batchInsert(Connection connection, String sql, List<T> rows, RowBinder<T> binder)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (T row : rows) {
                binder.bind(ps, row);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    record ReferenceSourceRow(String sourceId, String title, String sourceType, String issuer, String sourceUrl,
                               String localSourcePath, String parentSourceId, String documentVersion, String issuedAt,
                               String effectiveFrom, String effectiveTo, String retrievedAt, String verificationStatus,
                               String section, String printedPage, String pdfPage, String notes) {
    }

    record RuleRow(String ruleCode, String scopeType, String ingredientCodes, String expressionType,
                    String candidateExamples, String judgmentCategory, String applicationConditions,
                    String exceptions, String requiredEvidence, String severity, String sourceIds,
                    String caseIds, String ruleVersion, String reviewStatus) {
    }

    record AdCaseRow(String caseId, String caseType, String sourceId, String ingredientCodes,
                      String otherMainIngredients, String textKind, String quotedText, String sourceDecision,
                      String sourceReasonSummary, String ruleCodes, String printedPage, String pdfPage,
                      String verificationStatus, String notes) {
    }

    private static <T> List<T> readCsv(String resourcePath, Class<T> type) {
        CsvMapper csvMapper = new CsvMapper();
        CsvSchema schema = CsvSchema.emptySchema().withHeader().withNullValue("");
        try (InputStream in = V5__LoadRuleData.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("리소스를 찾을 수 없음: " + resourcePath);
            }
            return csvMapper.readerFor(type).with(schema).<T>readValues(in).readAll();
        } catch (IOException e) {
            throw new UncheckedIOException("참조데이터 로딩 실패: " + resourcePath, e);
        }
    }
}
