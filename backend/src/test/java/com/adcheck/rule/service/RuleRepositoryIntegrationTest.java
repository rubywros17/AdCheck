package com.adcheck.rule.service;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** H2 only: isolated synthetic relationship fixtures, rolled back after every test. */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:adcheck-rule-engine;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Transactional
class RuleRepositoryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleAnalysisService service;
    @Autowired RuleSelector selector;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void fixture() {
        jdbc.update("insert into ingredient_master(id, standard_name, ingredient_category, support_status) "
                + "values (9001, 'fixture A', 'FUNCTIONAL_INGREDIENT', 'SUPPORTED'), (9002, 'fixture B', 'FUNCTIONAL_INGREDIENT', 'SUPPORTED'), "
                + "(9003, 'fixture C', 'FUNCTIONAL_INGREDIENT', 'SUPPORTED')");
        insertRule(9100L, "C07_ABSOLUTE_EFFECT", "COMMON");
        insertRule(9101L, "FIXTURE_SHARED", "INGREDIENT_SPECIFIC");
        insertRule(9102L, "FIXTURE_OTHER", "INGREDIENT_SPECIFIC");
        jdbc.update("insert into rule_ingredients(rule_id, ingredient_master_id) values (9101,9001), (9101,9002), (9102,9003)");
        jdbc.update("insert into reference_sources(id, source_id, title, source_type, issuer, source_url, "
                + "document_version, verification_status, section, printed_page, pdf_page) "
                + "values (9200, 'FIXTURE-SOURCE', '출처 제목', 'GUIDELINE', '발행기관', null, 'v-test', 'UNVERIFIED', '절 1', '12-13', '14')");
        jdbc.update("insert into rule_sources(rule_id, reference_source_id) values (9100, 9200)");
    }

    @Test
    void selectsCommonAndRelatedRulesExcludesOtherIngredientsAndDeduplicates() {
        assertThat(selector.select(Set.of(9001L, 9002L)))
                .extracting(r -> r.getRuleCode()).containsExactly("C07_ABSOLUTE_EFFECT", "FIXTURE_SHARED");
        assertThat(selector.select(Set.of())).extracting(r -> r.getRuleCode()).containsExactly("C07_ABSOLUTE_EFFECT");
        assertThat(selector.select(Set.of(99999L))).extracting(r -> r.getRuleCode()).containsExactly("C07_ABSOLUTE_EFFECT");
    }

    @Test
    void resolvesActualBridgeMetadataIncludingNullUrlAndPageStrings() {
        var result = service.analyze(CommonRuleEvaluatorTest.request("이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다."));
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.evaluation().status()).isEqualTo(RuleEvaluation.Status.MATCHED);
            assertThat(match.sources()).singleElement().satisfies(source -> {
                assertThat(source.referenceSourceId()).isEqualTo(9200L);
                assertThat(source.sourceId()).isEqualTo("FIXTURE-SOURCE");
                assertThat(source.title()).isEqualTo("출처 제목");
                assertThat(source.sourceType()).isEqualTo("GUIDELINE");
                assertThat(source.issuer()).isEqualTo("발행기관");
                assertThat(source.sourceUrl()).isNull();
                assertThat(source.documentVersion()).isEqualTo("v-test");
                assertThat(source.verificationStatus()).isEqualTo("UNVERIFIED");
                assertThat(source.section()).isEqualTo("절 1");
                assertThat(source.printedPage()).isEqualTo("12-13");
                assertThat(source.pdfPage()).isEqualTo("14");
            });
        });
    }

    @Test
    void selectedUnsupportedRuleWithNoSourceIsExplicit() {
        var base = CommonRuleEvaluatorTest.request("text");
        var result = service.analyze(new RuleAnalysisRequest(base.claim(), Set.of("FIXTURE_SHARED"), Set.of(9001L), null));
        var match = result.matches().stream().filter(m -> m.ruleCode().equals("FIXTURE_SHARED")).findFirst().orElseThrow();
        assertThat(match.evaluation().status()).isEqualTo(RuleEvaluation.Status.REVIEW_REQUIRED);
        assertThat(match.candidatePresent()).isTrue();
        assertThat(match.diagnostics()).contains(RuleAnalysisResult.Diagnostic.SOURCE_MISSING);
    }

    @Test
    void serviceUsesThreeBatchQueriesWithMultipleIngredients() {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        var base = CommonRuleEvaluatorTest.request("text");
        var result = service.analyze(new RuleAnalysisRequest(base.claim(), Set.of(), Set.of(9001L, 9002L), null));
        assertThat(result.matches()).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    private void insertRule(Long id, String code, String scope) {
        var canonical = CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT");
        jdbc.update("insert into rules(id, rule_code, scope_type, expression_type, judgment_category, "
                        + "application_conditions, exceptions, severity, rule_version, review_status) values (?,?,?,?,?,?,?,?,?,?)",
                id, code, scope, canonical.getExpressionType(), canonical.getJudgmentCategory(),
                canonical.getApplicationConditions(), canonical.getExceptions(), canonical.getSeverity(), "0.1", "DRAFT");
    }
}
