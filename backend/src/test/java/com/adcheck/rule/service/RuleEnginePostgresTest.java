package com.adcheck.rule.service;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, SELECT-only verification of an already migrated and canonically loaded PostgreSQL DB. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only = on"
})
@EnabledIfEnvironmentVariable(named = "ADCHECK_POSTGRES_TEST", matches = "true")
@Transactional(readOnly = true)
class RuleEnginePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleAnalysisService service;

    @Test
    void actualPostgresCanonicalCountsAndIntegrity() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
        assertCount("ingredient_master", 607);
        assertCount("reference_sources", 15);
        assertCount("rules", 71);
        assertCount("rule_ingredients", 41);
        assertCount("rule_sources", 97);
        assertThat(count("select count(*) from rules where scope_type='COMMON'")).isEqualTo(30);
        assertThat(count("select count(*) from rules where scope_type='INGREDIENT_SPECIFIC'")).isEqualTo(41);
        assertThat(count("select count(*) from rules r where not exists (select 1 from rule_sources s where s.rule_id=r.id)"))
                .isZero();
        assertThat(count("select count(*) from rules r where scope_type='INGREDIENT_SPECIFIC' "
                + "and not exists (select 1 from rule_ingredients i where i.rule_id=r.id)")).isZero();
        for (String sql : List.of(
                "select count(*) from reference_sources c left join reference_sources p on p.id=c.parent_source_id where c.parent_source_id is not null and p.id is null",
                "select count(*) from rule_ingredients b left join rules r on r.id=b.rule_id where r.id is null",
                "select count(*) from rule_ingredients b left join ingredient_master i on i.id=b.ingredient_master_id where i.id is null",
                "select count(*) from rule_sources b left join rules r on r.id=b.rule_id where r.id is null",
                "select count(*) from rule_sources b left join reference_sources s on s.id=b.reference_source_id where s.id is null")) {
            assertThat(count(sql)).as(sql).isZero();
        }
    }

    @Test
    void canonicalCommonEvaluationAndRealMetadata() {
        var result = service.analyze(CommonRuleEvaluatorTest.request("이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다."));
        assertThat(result.matches()).hasSize(30);
        assertThat(result.diagnostics()).contains(RuleAnalysisResult.Diagnostic.INGREDIENT_SPECIFIC_NOT_EVALUATED);
        var matched = result.matches().stream().filter(m -> m.ruleCode().equals("C07_ABSOLUTE_EFFECT")).findFirst().orElseThrow();
        assertThat(matched.evaluation().status()).isEqualTo(RuleEvaluation.Status.MATCHED);
        assertThat(matched.sources()).extracting(s -> s.sourceId()).containsExactly("LAW-01", "REVIEW-02", "REVIEW-03");
        for (var match : result.matches()) {
            assertThat(match.diagnostics()).doesNotContain(RuleAnalysisResult.Diagnostic.SOURCE_MISSING);
            for (var source : match.sources()) {
                var db = jdbc.queryForMap("select * from reference_sources where id=?", source.referenceSourceId());
                assertThat(source.sourceId()).isEqualTo(db.get("source_id"));
                assertThat(source.title()).isEqualTo(db.get("title"));
                assertThat(source.sourceType()).isEqualTo(db.get("source_type"));
                assertThat(source.issuer()).isEqualTo(db.get("issuer"));
                assertThat(source.sourceUrl()).isEqualTo(db.get("source_url"));
                assertThat(source.documentVersion()).isEqualTo(db.get("document_version"));
                assertThat(source.verificationStatus()).isEqualTo(db.get("verification_status"));
                assertThat(source.section()).isEqualTo(db.get("section"));
                assertThat(source.printedPage()).isEqualTo(db.get("printed_page"));
                assertThat(source.pdfPage()).isEqualTo(db.get("pdf_page"));
            }
        }
    }

    @Test
    void selectsOnlyActualConfirmedIngredientBridges() {
        var ids = new HashSet<>(jdbc.queryForList("select distinct ingredient_master_id from rule_ingredients order by ingredient_master_id limit 2", Long.class));
        assertThat(ids).hasSize(2);
        var base = CommonRuleEvaluatorTest.request("매일 식사 대신 이 제품만 드세요.");
        var result = service.analyze(new RuleAnalysisRequest(base.claim(), Set.of(), ids, null));
        var expected = new HashSet<>(jdbc.queryForList("select id from rules where scope_type='COMMON'", Long.class));
        for (Long id : ids) {
            expected.addAll(jdbc.queryForList("select rule_id from rule_ingredients where ingredient_master_id=?", Long.class, id));
        }
        assertThat(result.matches()).extracting(m -> m.ruleId()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.matches()).extracting(m -> m.ruleId()).doesNotHaveDuplicates();
        assertThat(result.matches().stream().filter(m -> m.scopeType().equals("INGREDIENT_SPECIFIC")))
                .allSatisfy(m -> assertThat(m.evaluation().status()).isEqualTo(RuleEvaluation.Status.REVIEW_REQUIRED));
        assertThat(result.matches().stream().filter(m -> m.ruleCode().equals("C24_OVERCONSUMPTION")))
                .singleElement().satisfies(m -> assertThat(m.evaluation().status()).isEqualTo(RuleEvaluation.Status.MATCHED));
    }

    @Test
    void canonicalExceptionAndMissingEvidenceBehaveConservatively() {
        var result = service.analyze(CommonRuleEvaluatorTest.request("이 제품은 균형 잡힌 식사를 대체할 수 없습니다."));
        assertThat(result.matches().stream().filter(m -> m.ruleCode().equals("C24_OVERCONSUMPTION")))
                .singleElement().satisfies(m -> assertThat(m.evaluation().status()).isEqualTo(RuleEvaluation.Status.NOT_MATCHED));
        assertThat(result.matches().stream().filter(m -> m.ruleCode().equals("C05_FUNCTION_EXCEED")))
                .singleElement().satisfies(m -> assertThat(m.evaluation().reasonCode())
                        .isEqualTo(RuleEvaluation.ReasonCode.OFFICIAL_FUNCTION_DATA_INCOMPLETE));
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private void assertCount(String table, int expected) { assertThat(count("select count(*) from " + table)).as(table).isEqualTo(expected); }
}
