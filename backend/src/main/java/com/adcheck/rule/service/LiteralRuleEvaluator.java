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

/**
 * 68/71 미구현 규칙 중 "리터럴형"(7개, 키워드·숫자 패턴이 핵심) + 혼합 중에서도 예외가
 * 텍스트만으로 판단 가능한 2개(M03_ALCOHOL, S01_PAIN)를 다루는 정규식 기반 평가기.
 * {@code CommonRuleEvaluator}와 완전히 같은 스타일 — 매번 새 문장을 해석하는 게 아니라
 * bounded 전체 문장 템플릿({@code .matches()})만 잡는다.
 *
 * <p>혼합 6개 중 나머지 4개(P03_DISEASE_GUT, M02_LIVER_MARKER, S02_BODY_AREA, L02_UV)는
 * 예외 조건이 레이아웃 정보("구획", "부위 강조 여부") 또는 의도 판단("일반 생활정보인지")을
 * 요구해서 정규식으로 옮길 수 없다 — {@code AiRuleEvaluator}가 계속 담당한다.
 *
 * <p>원래 리터럴형이던 C22_SUPERLATIVE, C30_NATURAL_FREE도 {@code AiRuleEvaluator}로
 * 이관했다 — 정규식으로는 예외 조건(조건부 표현 여부, 제형·원료별 고시 예외 적용 여부)을 절대
 * 확정할 수 없어 항상 REVIEW_REQUIRED만 반환했는데, RAG grounding(실제 근거 문서 원문 제공)이
 * 붙은 AI 판정이면 이 두 규칙에서 실제로 MATCHED/NOT_MATCHED를 낼 수 있을 것으로 보고 옮겼다.
 *
 * <p><b>등록 완료(2026-09-17)</b> — 정규식 기반이라 LLM 노이즈가 없어 이미 충분히 검증됐다고
 * 보고, {@code AiRuleEvaluator} 파일럿(9개)과 함께 <code>adcheck.rule-judge.enabled-rule
 * -codes</code> allowlist에 이 9개 전부를 넣어 실제 파이프라인에 연결했다.
 */
@Component
public class LiteralRuleEvaluator implements RuleEvaluator {

    private record Definition(String version, String conditions, String exceptions) {
        boolean matches(Rule rule) {
            return version.equals(rule.getRuleVersion())
                    && conditions.equals(rule.getApplicationConditions())
                    && Objects.equals(exceptions, rule.getExceptions());
        }
    }

    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            Map.entry("C08_RESULT_TIME_AMOUNT", new Definition("0.1",
                    "특정 기간 또는 섭취 결과를 일반 소비자에게 보장하는 제품 카피인지",
                    "적정 인체시험 인용, 단순 포장분량 '30일분'과 구분")),
            Map.entry("C09_COMPLETE_SOLUTION", new Definition("0.1",
                    "건강 문제를 제품 섭취만으로 해결·치료 대체할 것처럼 표현하는지",
                    "'두 제품을 한 번에 섭취' 같은 편의성과 구분")),
            Map.entry("G02_PERIOD", new Definition("0.1",
                    "특정 기간 내 결과를 기대하게 하는지",
                    "단순 포장 수량·섭취 일정인지 구분")),
            Map.entry("T01_WEIGHT_RESULT", new Definition("0.1",
                    "체중감량 및 수치 효과 보장으로 인식되는지",
                    "체험담과 시험 수치 인용 구획 구분")),
            Map.entry("T02_DETOX", new Definition("0.1",
                    "체지방 기능을 미인정 해독 기능으로 확장하는지",
                    "이름·상표에 포함된 경우도 광고 전체 확인")),
            Map.entry("T03_DIET_DRUG", new Definition("0.1",
                    "의약품 오인 용어 사용 여부",
                    "광고 문맥에서 제품 지칭인지 확인")),
            Map.entry("B05_ANTIBACTERIAL_WORD", new Definition("0.1",
                    "인정 기능성의 '항균'을 잘못 표기했는지",
                    "문구 정정 항목. 오타만으로 높은 건강 위해 또는 위법 확정 금지")),
            Map.entry("M03_ALCOHOL", new Definition("0.1",
                    "알코올 보호·즉시효과·숙취 효과를 주장하는지",
                    "다른 원료에 인정된 알코올 손상 보호 기능이 있어도 숙취 효과로 확대 불가")),
            Map.entry("S01_PAIN", new Definition("0.1",
                    "관절 건강을 통증·증상 치료로 연결하는지",
                    "번개도안 등 비텍스트 신호는 텍스트 수집만으로 확인 불가"))
    );

    // C08_RESULT_TIME_AMOUNT
    private static final Pattern C08_DAY_RESULT = Pattern.compile("\\d+일이면 .*(효과|개선)(를|을)? ?(봅니다|봐요|됩니다)[.!]?");
    private static final Pattern C08_KG_LOSS = Pattern.compile("\\d+(\\.\\d+)?\\s?kg (감량했어요|감량합니다|빠졌어요)[.!]?");
    private static final Pattern C08_PACKAGE_QTY = Pattern.compile("\\d+일분(입니다)?[.!]?");

    // C09_COMPLETE_SOLUTION
    private static final Pattern C09_COMPLETE = Pattern.compile("이제 (고민|관리) 끝[.!]?");
    private static final Pattern C09_ONE_SHOT = Pattern.compile("한 번에 끝[.!]?");
    private static final Pattern C09_CONVENIENCE = Pattern.compile("두 제품을 한 번에 섭취하세요[.!]?");

    // G02_PERIOD
    private static final Pattern G02_WEEK_RESULT = Pattern.compile("\\d+주(만에|이면) .+(빠집니다|빠져요|효과)[.!]?");
    private static final Pattern G02_DAY_DIET = Pattern.compile("\\d+일 다이어트[.!]?");
    private static final Pattern G02_PACKAGE_QTY = Pattern.compile("\\d+주분(입니다)?[.!]?");

    // T01_WEIGHT_RESULT
    private static final Pattern T01_KG_RESULT = Pattern.compile("\\d+(\\.\\d+)?\\s?kg (빠졌어요|감량했어요|뺐어요)[.!]?");

    // T02_DETOX
    private static final Pattern T02_DETOX_CLAIM = Pattern.compile(".*(클렌즈|디톡스).*(효과|해줍니다|도와줍니다)[.!]?");

    // T03_DIET_DRUG
    private static final Pattern T03_DRUG_TERM =
            Pattern.compile("(이 제품은|본 제품은)? ?(다이어트제|다이어트 보조제|체중감량제)(입니다)?[.!]?");

    // B05_ANTIBACTERIAL_WORD
    private static final Pattern B05_TYPO = Pattern.compile(".*향균.*[.!]?");

    // M03_ALCOHOL
    private static final Pattern M03_HANGOVER =
            Pattern.compile(".*(회식 전|숙취)(에|를|엔)? .*(끝|해소|도움)(이 됩니다|을 줍니다|이 돼요)?[.!]?");
    private static final Pattern M03_LIVER_PROTECTION_ONLY =
            Pattern.compile("(간 건강|알코올로 인한 손상)에? ?도움을 줄 수 있습니다[.!]?");

    // S01_PAIN
    private static final Pattern S01_SYMPTOM_TREATMENT =
            Pattern.compile(".*(뻣뻣함|찌릿함|통증|저림|욱신).*(사라졌어요|없어졌어요|치료|낫습니다)[.!]?");
    private static final Pattern S01_OFFICIAL_FUNCTION = Pattern.compile("관절[/·]?연골 건강(에)? ?도움을 줄 수 있습니다[.!]?");

    @Override
    public Set<String> ruleCodes() {
        return DEFINITIONS.keySet();
    }

    @Override
    public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
        Definition definition = DEFINITIONS.get(rule.getRuleCode());
        if (definition == null || !definition.matches(rule)) {
            return review(RULE_DEFINITION_CHANGED, "검토한 canonical 0.1 조건·예외와 다릅니다.");
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
        String text = claim.text().strip();
        return switch (rule.getRuleCode()) {
            case "C08_RESULT_TIME_AMOUNT" -> evaluateResultTimeAmount(text);
            case "C09_COMPLETE_SOLUTION" -> evaluateCompleteSolution(text);
            case "G02_PERIOD" -> evaluatePeriod(text);
            case "T01_WEIGHT_RESULT" -> evaluateWeightResult(text);
            case "T02_DETOX" -> evaluateDetox(text);
            case "T03_DIET_DRUG" -> evaluateDietDrug(text);
            case "B05_ANTIBACTERIAL_WORD" -> evaluateAntibacterialWord(text);
            case "M03_ALCOHOL" -> evaluateAlcohol(text);
            case "S01_PAIN" -> evaluatePain(text);
            default -> throw new IllegalStateException("Registered rule has no evaluator implementation");
        };
    }

    private RuleEvaluation evaluateResultTimeAmount(String text) {
        if (C08_PACKAGE_QTY.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "단순 포장분량 표기이며 결과를 보장하지 않습니다.");
        }
        if (C08_DAY_RESULT.matcher(text).matches() || C08_KG_LOSS.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED,
                    "특정 기간 또는 수치 결과를 일반 소비자에게 보장하는 표현입니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "기간·결과 보장 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateCompleteSolution(String text) {
        if (C09_CONVENIENCE.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "제품 섭취 편의성 안내이며 문제 해결 보장이 아닙니다.");
        }
        if (C09_COMPLETE.matcher(text).matches() || C09_ONE_SHOT.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED,
                    "건강 문제를 제품 섭취만으로 해결하는 것처럼 표현합니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "완전 해결 표방 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluatePeriod(String text) {
        if (G02_PACKAGE_QTY.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "단순 포장 수량 표기이며 기간 내 결과를 보장하지 않습니다.");
        }
        if (G02_WEEK_RESULT.matcher(text).matches() || G02_DAY_DIET.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "특정 기간 내 결과를 기대하게 하는 표현입니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "기간 내 결과 보장 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateWeightResult(String text) {
        if (T01_KG_RESULT.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "체중감량 수치 결과를 보장하는 표현입니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "체중감량 수치 보장 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateDetox(String text) {
        if (T02_DETOX_CLAIM.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "체지방 기능을 미인정 해독 기능으로 확장하는 표현입니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "해독 기능 확장 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateDietDrug(String text) {
        if (T03_DRUG_TERM.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "의약품으로 오인할 수 있는 용어를 제품 지칭에 사용합니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "의약품 오인 용어 사용 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateAntibacterialWord(String text) {
        if (B05_TYPO.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "'항균'의 오기인 '향균' 표기가 확인됩니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "표기 오류 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluateAlcohol(String text) {
        if (M03_LIVER_PROTECTION_ONLY.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "간 건강 인정기능성 범위 내 표현이며 숙취·즉시효과 확대가 없습니다.");
        }
        if (M03_HANGOVER.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "알코올 보호·즉시효과·숙취 효과를 주장합니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "숙취·즉시효과 주장 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private RuleEvaluation evaluatePain(String text) {
        if (S01_OFFICIAL_FUNCTION.matcher(text).matches()) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED, "관절·연골 건강 인정기능성 범위 내 표현입니다.");
        }
        if (S01_SYMPTOM_TREATMENT.matcher(text).matches()) {
            return new RuleEvaluation(MATCHED, SUPPORTED_CONDITION_CONFIRMED, "관절 건강을 통증·증상 치료 효과로 직접 연결합니다.");
        }
        return review(OUTSIDE_SUPPORTED_LANGUAGE, "통증·증상 치료 연결 여부를 지원 문장 범위에서 확정할 수 없습니다.");
    }

    private static RuleEvaluation review(RuleEvaluation.ReasonCode code, String reason) {
        return new RuleEvaluation(REVIEW_REQUIRED, code, reason);
    }
}
