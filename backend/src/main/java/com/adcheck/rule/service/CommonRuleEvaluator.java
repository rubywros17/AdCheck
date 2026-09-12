package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import static com.adcheck.rule.service.RuleEvaluation.Status.*;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.*;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.*;

/** Deliberately bounded full-sentence grammar, not a general Korean semantic classifier. */
@Component
public class CommonRuleEvaluator implements RuleEvaluator {
    private record Definition(String version, String conditions, String exceptions) {
        boolean matches(Rule rule) {
            return "COMMON".equals(rule.getScopeType()) && version.equals(rule.getRuleVersion())
                    && conditions.equals(rule.getApplicationConditions())
                    && Objects.equals(exceptions, rule.getExceptions()) && rule.getRequiredEvidence() == null;
        }
    }

    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            Map.entry("C05_FUNCTION_EXCEED", new Definition("0.1", "제품 인정 기능성의 대상·작용·결과·조건과 주장 의미를 비교", "다른 주원료에 해당 기능성이 있는지 확인. '도움'이라는 말이 있어도 범위 초과 가능")),
            Map.entry("C07_ABSOLUTE_EFFECT", new Definition("0.1", "수식 대상이 건강 효과이고 개인차 없이 결과를 확정하는지 확인", "원료함량·영양성분 기준치·배송 안내 등과 구분. 예시 조합 일부는 AdCheck 작성 예시")),
            Map.entry("C24_OVERCONSUMPTION", new Definition("0.1", "권장량 초과 조장, 균형식 대체, 잘못된 식습관 유지 유도 여부", "단순 영양성분 함량 설명과 음식 전체 대체를 구분")));

    private static final Pattern GUARANTEE = Pattern.compile(
            "이 제품을 섭취하면 누구나 (피로 개선|체지방 감소|기억력 개선) 효과를 (100%|반드시) 얻습니다[.!]?");
    private static final Pattern SHORT_GUARANTEE = Pattern.compile("100% 효과를 보장합니다[.!]?");
    private static final Pattern INGREDIENT_ONLY = Pattern.compile("원료 100% 사용[.!]?");
    private static final Pattern NO_SHORT_GUARANTEE = Pattern.compile("100% 효과를 보장하지 않습니다[.!]?");
    private static final Pattern NO_GUARANTEE = Pattern.compile(
            "이 제품은 (피로 개선|체지방 감소|기억력 개선) 효과를 보장하지 않습니다[.!]?");
    private static final Pattern NON_HEALTH_PERCENT = Pattern.compile(
            "(원료 함량은|영양성분 기준치는) 100%입니다[.!]?");
    private static final Pattern MEAL_REPLACEMENT = Pattern.compile(
            "(균형 잡힌 식사 대신|매일 식사 대신) 이 제품만 (드세요|섭취하세요)[.!]?");
    private static final Pattern NO_MEAL_REPLACEMENT = Pattern.compile(
            "이 제품은 균형 잡힌 식사를 대체할 수 없습니다[.!]?");
    private static final Pattern NUTRIENT_AMOUNT = Pattern.compile(
            "이 제품의 1일 섭취량에는 (비타민 C가|칼슘이|아연이) [0-9]+(?:[.][0-9]+)? mg 들어 있습니다[.!]?");

    @Override
    public Set<String> ruleCodes() { return DEFINITIONS.keySet(); }

    @Override
    public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
        Definition definition = DEFINITIONS.get(rule.getRuleCode());
        if (definition == null || !definition.matches(rule)) {
            return review(RULE_DEFINITION_CHANGED, "검토한 canonical 0.1 조건·예외·필요근거와 다릅니다.");
        }
        var claim = request.claim();
        if (claim.text() == null || claim.text().isBlank()) {
            return review(MISSING_CLAIM_TEXT, "평가할 주장 원문이 없습니다.");
        }
        if (claim.context() == UNKNOWN || claim.contextEvidence() == null || claim.contextEvidence().isBlank()) {
            return review(CONTEXT_UNVERIFIED, "전체 주장과 주변 광고에서 제품 귀속·인용·부정 문맥 확인이 필요합니다.");
        }
        if (claim.context() == NON_PRODUCT_INFORMATION) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED,
                    "제품 효과에 귀속되지 않는 독립 정보임이 확인되었습니다. 해당 주장에만 적용됩니다.");
        }
        String text = normalize(claim.text());
        return switch (rule.getRuleCode()) {
            case "C07_ABSOLUTE_EFFECT" -> evaluateGuarantee(text, claim.context());
            case "C24_OVERCONSUMPTION" -> evaluateConsumption(text);
            case "C05_FUNCTION_EXCEED" -> evaluateOfficialFunction(text, request);
            default -> throw new IllegalStateException("Registered rule has no evaluator implementation");
        };
    }

    private RuleEvaluation evaluateGuarantee(String text, RuleAnalysisRequest.Context context) {
        if (SHORT_GUARANTEE.matcher(text).matches()) {
            if (context != PRODUCT_HEALTH_EFFECT_COPY) {
                return review(CONTEXT_UNVERIFIED, "효과의 대상이 제품의 건강 효과인지 추가 확인이 필요합니다.");
            }
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED,
                    "주변 문맥에서 제품의 건강 효과임이 확인되었고 전체 문장이 100% 효과를 보장합니다.");
        }
        if (NO_GUARANTEE.matcher(text).matches() || NO_SHORT_GUARANTEE.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "건강 효과 보장을 명시적으로 부정합니다.");
        }
        if (NON_HEALTH_PERCENT.matcher(text).matches() || INGREDIENT_ONLY.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, CONDITION_NOT_MET, "100%의 대상은 원료함량 또는 영양성분 기준치입니다.");
        }
        if (GUARANTEE.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED,
                    "제품 섭취의 건강 효과를 누구나 얻는다고 확정하며, 확인한 전체 문장에 함량·배송 예외가 없습니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "건강 효과 수식·개인차·예외를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateConsumption(String text) {
        if (NO_MEAL_REPLACEMENT.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "균형 잡힌 식사 대체를 명시적으로 부정합니다.");
        }
        if (NUTRIENT_AMOUNT.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, CONDITION_NOT_MET, "단순 영양성분 함량 설명이며 식사 대체 권유가 아닙니다.");
        }
        if (MEAL_REPLACEMENT.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED,
                    "매일의 식사 또는 균형식을 제품만으로 대체하도록 권유하며 단순 함량 설명이 아닙니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "식사 대체 외 과량 섭취·식습관 및 다른 문장 형태는 추가 검토가 필요합니다.");
    }

    private RuleEvaluation evaluateOfficialFunction(String text, RuleAnalysisRequest request) {
        var official = request.officialFunctions();
        var ids = request.confirmedIngredientMasterIds();
        if (ids.isEmpty() || !official.productApplicabilityVerified() || !official.allMainIngredientsCovered()
                || official.values().isEmpty()
                || official.values().stream().anyMatch(f -> f.ingredientMasterId() == null
                    || !ids.contains(f.ingredientMasterId()) || f.officialFunctionText() == null
                    || f.officialFunctionText().isBlank())
                || !official.values().stream().map(f -> f.ingredientMasterId()).collect(java.util.stream.Collectors.toSet())
                    .containsAll(ids)) {
            return review(OFFICIAL_FUNCTION_DATA_INCOMPLETE,
                    "확정된 모든 주원료의 공식 기능성 및 함량·섭취량·제품별 적용 확인이 필요합니다.");
        }
        if (official.values().stream().anyMatch(f -> normalize(f.officialFunctionText()).equals(text))) {
            return new RuleEvaluation(NOT_MATCHED, OFFICIAL_FUNCTION_EXACT_MATCH,
                    "제품 적용이 확인된 공식 기능성 원문과 정확히 일치합니다. 이 주장의 범위 초과만 평가합니다.");
        }
        return review(SEMANTIC_COMPARISON_REQUIRED, "문구 불일치만으로 범위 초과를 확정할 수 없어 의미·다른 주원료 비교가 필요합니다.");
    }

    private static String normalize(String text) { return text.strip().replaceAll("\\s+", " "); }

    private static RuleEvaluation review(RuleEvaluation.ReasonCode code, String reason) {
        return new RuleEvaluation(REVIEW_REQUIRED, code, reason);
    }
}
