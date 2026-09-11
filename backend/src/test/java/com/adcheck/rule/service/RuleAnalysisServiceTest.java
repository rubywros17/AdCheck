package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.repository.RuleRepository;
import com.adcheck.rule.repository.RuleIngredientRepository;
import com.adcheck.rule.repository.RuleSourceRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.adcheck.rule.service.RuleAnalysisResult.Diagnostic.*;
import static com.adcheck.rule.service.RuleEvaluation.Status.*;

class RuleAnalysisServiceTest {
    private final RuleRepository rules = mock(RuleRepository.class);
    private final RuleIngredientRepository ingredients = mock(RuleIngredientRepository.class);
    private final RuleSourceRepository sources = mock(RuleSourceRepository.class);
    private final RuleSelector selector = new RuleSelector(rules, ingredients);
    private final RuleEvaluatorRegistry registry = new RuleEvaluatorRegistry(List.of(new CommonRuleEvaluator()));
    private final RuleAnalysisService service = new RuleAnalysisService(selector, registry, new RuleSourceResolver(sources));

    @Test
    void noIngredientStillEvaluatesCommonAndReportsMissingSourceAndDraft() {
        Rule rule = CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT");
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(rule));
        var result = service.analyze(CommonRuleEvaluatorTest.request("이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다."));
        assertThat(result.claimId()).isEqualTo("claim-1");
        assertThat(result.diagnostics()).containsExactly(INGREDIENT_SPECIFIC_NOT_EVALUATED);
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.claimId()).isEqualTo("claim-1");
            assertThat(match.ruleId()).isEqualTo(rule.getId());
            assertThat(match.evaluation().status()).isEqualTo(MATCHED);
            assertThat(match.sources()).isEmpty();
            assertThat(match.diagnostics()).containsExactly(SOURCE_MISSING, DRAFT_RULE);
        });
        verifyNoInteractions(ingredients);
        verify(sources).findAllWithSourceByRuleIds(List.of(rule.getId()));
    }

    @Test
    void removesDuplicatesAcrossIngredientLinks() {
        var common = CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT");
        var specific = mock(Rule.class);
        when(specific.getId()).thenReturn(999L);
        when(specific.getRuleCode()).thenReturn("I_FIXTURE");
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(common));
        when(ingredients.findIngredientSpecificRules(Set.of(1L, 2L))).thenReturn(List.of(specific, specific));
        assertThat(selector.select(Set.of(1L, 2L))).containsExactly(common, specific);
        verify(ingredients).findIngredientSpecificRules(Set.of(1L, 2L));
    }

    @Test
    void unsupportedRuleRequiresReviewEvenWithoutCandidate() {
        var result = registry.evaluate(CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION"), CommonRuleEvaluatorTest.request("normal"));
        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.UNSUPPORTED_RULE);
    }

    @Test
    void duplicateRegistryRegistrationFailsFast() {
        assertThatThrownBy(() -> new RuleEvaluatorRegistry(List.of(new CommonRuleEvaluator(), new CommonRuleEvaluator())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void databaseFailureIsNotHiddenAsReview() {
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON"))
                .thenThrow(new DataAccessResourceFailureException("test database unavailable"));
        assertThatThrownBy(() -> service.analyze(CommonRuleEvaluatorTest.request("text")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void evaluatorProgrammingFailureIsNotHiddenAsReview() {
        RuleEvaluator broken = mock(RuleEvaluator.class);
        when(broken.ruleCodes()).thenReturn(Set.of("C07_ABSOLUTE_EFFECT"));
        var rule = CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT");
        var request = CommonRuleEvaluatorTest.request("text");
        when(broken.evaluate(rule, request)).thenThrow(new IllegalStateException("bug"));
        assertThatThrownBy(() -> new RuleEvaluatorRegistry(List.of(broken)).evaluate(rule, request))
                .isInstanceOf(IllegalStateException.class);
    }
}
