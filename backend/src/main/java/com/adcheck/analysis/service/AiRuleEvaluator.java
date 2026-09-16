package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RiskSignalContext;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import com.adcheck.rule.service.RuleEvaluator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Context.NON_PRODUCT_INFORMATION;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.UNKNOWN;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.CONTEXT_UNVERIFIED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.EXCEPTION_CONFIRMED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.MISSING_CLAIM_TEXT;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.SEMANTIC_COMPARISON_REQUIRED;
import static com.adcheck.rule.service.RuleEvaluation.Status.NOT_MATCHED;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;

/**
 * 68/71 미구현 규칙 중 해석형(33) + 혼합 중 예외가 레이아웃·의도 판단을 요구하는 4개
 * (P03_DISEASE_GUT, M02_LIVER_MARKER, S02_BODY_AREA, L02_UV) = 37개를 대상으로 하는
 * AI 기반 {@link RuleEvaluator} 초안 — "직접·암시 주장인지", "확장하는지" 같은 매번 새
 * 문장을 해석해야 하는 규칙군(자세한 분류 근거는 pipeline 아티팩트 ④단계 참고)을 다룬다.
 * 리터럴형(9개)과 혼합 중 예외가 텍스트만으로 판단 가능한 2개(M03_ALCOHOL, S01_PAIN)는
 * {@code LiteralRuleEvaluator}(정규식 기반)가 대신 담당하고, 데이터부재형(~19개)은
 * 대상이 아니다.
 *
 * <p><b>아직 어디에도 등록하지 않았다</b>({@code @Component} 없음, {@code RuleEvaluatorRegistry}가
 * 이 클래스를 모른다) — 로드맵상 다음 단계(검증 데이터셋으로 정확도 확인, 리터럴형 확정)를
 * 마친 뒤 등록할 예정이라 지금은 프롬프트 설계를 검증하는 용도로만 존재한다.
 *
 * <p>AI 호출 전 사전 체크 3가지는 {@code CommonRuleEvaluator}와 정확히 동일한 로직을 그대로
 * 재사용한다 — 이 필터를 통과한 것만 실제로 Gemini를 호출해서, 판단할 필요가 없는 요청에는
 * 애초에 호출이 안 나가게 한다. {@code CommonRuleEvaluator}에 있는 canonical Definition
 * drift-check(하드코딩된 조건문과 DB 값이 일치하는지 검증)는 여기엔 없다 — 정규식과 달리
 * 이 프롬프트는 {@code rule.getApplicationConditions()} 등을 매 호출마다 DB에서 그대로
 * 읽어 쓰므로, CSV/DB 값이 바뀌어도 구조적으로 어긋날 일이 없다.
 *
 * <p><b>배치 제약(중요)</b>: {@link RuleEvaluator#evaluate(Rule, RuleAnalysisRequest)}는
 * 규칙 1개당 호출 1번이라, 이 인터페이스를 그대로 구현하는 한 "Claim 1건에 적용되는 규칙
 * 전체를 한 프롬프트에 묶는" 진짜 배치는 불가능하다 — Claim 1건에 이 39개 규칙 중 여러 개가
 * 적용되면 적용된 개수만큼 Gemini 호출이 그대로 나간다. 진짜 배치를 하려면
 * {@code RuleEvaluator}에 여러 {@link Rule}을 한 번에 받는 메서드를 추가하고
 * {@code RuleAnalysisService}도 그걸 쓰도록 바꿔야 하는데, 이건 Backend Rule Engine
 * 인터페이스 자체를 바꾸는 결정이라 팀 확인 후 별도로 진행한다.
 */
public class AiRuleEvaluator implements RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AiRuleEvaluator.class);

    private static final Set<String> TARGET_RULE_CODES = Set.of(
            // COMMON 해석형(10)
            "C01_DISEASE_PREVENTION", "C02_DISEASE_TREATMENT", "C03_MEDICINE_CONFUSION",
            "C04_DISEASE_INFO_LINK", "C11_SUB_INGREDIENT_FUNCTION", "C13_TESTIMONIAL",
            "C14_EXPERT_ENDORSEMENT", "C21_UNFAIR_COMPARISON", "C27_FUNCTION_SYNERGY",
            "C28_TARGET_SPECIALIZATION",
            // INGREDIENT_SPECIFIC 해석형(23)
            "P01_VAGINAL_SCOPE", "P05_INFANT", "E01_VESSEL", "E02_OTHER_FUNCTION",
            "E03_GENERATION", "E04_ALIAS", "R01_COLD", "R02_MENOPAUSE", "G01_EASY_DIET",
            "G03_WEIGHT_FAT", "G04_SATIETY_COFFEE", "G05_GLUCOSE_DIET", "M01_FATIGUE",
            "M04_REGEN_CANCER", "T04_ANTIAGING", "S04_SEASON", "O01_SEXUAL",
            "O02_ENERGY_EXPANSION", "B01_IMMUNE_INFLAMMATION", "B02_VIRUS",
            "B03_OTHER_ORAL", "L01_VISION", "L03_EYE_DISEASE",
            // 혼합 중 텍스트만으로 예외 판단 불가한 4개 (M03_ALCOHOL, S01_PAIN은 LiteralRuleEvaluator 담당)
            "P03_DISEASE_GUT", "M02_LIVER_MARKER", "S02_BODY_AREA", "L02_UV"
    );

    private static final String STATUS_VALUES = "MATCHED, NOT_MATCHED, REVIEW_REQUIRED";
    private static final String REASON_CODE_VALUES =
            "SUPPORTED_CONDITION_CONFIRMED, CONDITION_NOT_MET, EXCEPTION_CONFIRMED, "
                    + "SEMANTIC_COMPARISON_REQUIRED, OFFICIAL_FUNCTION_DATA_INCOMPLETE, "
                    + "OFFICIAL_FUNCTION_EXACT_MATCH, OUTSIDE_SUPPORTED_LANGUAGE";

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiRuleEvaluator(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    @Override
    public Set<String> ruleCodes() {
        return TARGET_RULE_CODES;
    }

    @Override
    public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
        RuleAnalysisRequest.Claim claim = request.claim();
        if (claim.text() == null || claim.text().isBlank()) {
            return new RuleEvaluation(REVIEW_REQUIRED, MISSING_CLAIM_TEXT, "평가할 주장 원문이 없습니다.");
        }
        if (claim.context() == UNKNOWN || claim.contextEvidence() == null || claim.contextEvidence().isBlank()) {
            return new RuleEvaluation(REVIEW_REQUIRED, CONTEXT_UNVERIFIED,
                    "전체 주장과 주변 광고에서 제품 귀속·인용·부정 문맥 확인이 필요합니다.");
        }
        if (claim.context() == NON_PRODUCT_INFORMATION) {
            return new RuleEvaluation(NOT_MATCHED, EXCEPTION_CONFIRMED,
                    "제품 효과에 귀속되지 않는 독립 정보임이 확인되었습니다. 해당 주장에만 적용됩니다.");
        }

        String prompt = buildPrompt(rule, request);
        String rawResponse = geminiClient.generate(prompt, true);
        return parseResponse(rawResponse);
    }

    private String buildPrompt(Rule rule, RuleAnalysisRequest request) {
        RuleAnalysisRequest.Claim claim = request.claim();
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 건강기능식품 광고 문구가 특정 규칙에 해당하는지 판정하는 검수 도구입니다.\n\n");

        sb.append("[판단 기준]\n");
        sb.append("분류: ").append(rule.getJudgmentCategory()).append('\n');
        sb.append("적용 조건: ").append(rule.getApplicationConditions()).append('\n');
        if (isNotBlank(rule.getExceptions())) {
            sb.append("예외 사항: ").append(rule.getExceptions()).append('\n');
        }
        if (isNotBlank(rule.getCandidateExamples())) {
            sb.append("참고 예시(전체 목록 아님, 이런 것도 해당할 수 있다는 힌트일 뿐): ")
                    .append(rule.getCandidateExamples()).append('\n');
        }
        if (isNotBlank(rule.getRequiredEvidence())) {
            sb.append("필요 근거(참고용 — 지금 판단엔 이 근거 자료가 없을 수 있음): ")
                    .append(rule.getRequiredEvidence()).append('\n');
        }
        sb.append('\n');

        sb.append("[판단 대상 Claim]\n");
        sb.append("문장: ").append(claim.text()).append('\n');
        sb.append("문맥: ").append(claim.context())
                .append(" (근거: ").append(claim.contextEvidence()).append(")\n\n");

        List<RuleOfficialFunctionContext> officialFunctions = request.officialFunctions().values();
        if (!officialFunctions.isEmpty()) {
            sb.append("[확정된 공식 기능성] (참고용 — 이 성분들에 실제로 인정된 기능성)\n");
            for (RuleOfficialFunctionContext fn : officialFunctions) {
                sb.append("- ").append(fn.canonicalName()).append(": ").append(fn.officialFunctionText()).append('\n');
            }
            sb.append('\n');
        }

        List<RiskSignalContext> relatedSignals = request.riskSignals().stream()
                .filter(rs -> rs.relatesTo(rule.getJudgmentCategory()))
                .toList();
        if (!relatedSignals.isEmpty()) {
            sb.append("[AI#1이 미리 표시해둔 위험 신호] (참고용, 최종 판단은 아님)\n");
            for (RiskSignalContext rs : relatedSignals) {
                sb.append("- [").append(rs.signalType()).append("] ").append(rs.text()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("MATCHED는 \"이 Claim이 분류(").append(rule.getJudgmentCategory())
                .append(")가 우려하는 광고 위반 패턴에 실제로 해당한다\"는 뜻입니다. ");
        sb.append("[적용 조건]은 그 위반 여부를 판단하는 기준일 뿐, 조건 문장이 문자 그대로 참이라고 해서 ");
        sb.append("무조건 MATCHED가 되는 건 아닙니다 — 조건을 충족하는 것 자체가 오히려 정상적인 표시(위반 아님)를 ");
        sb.append("뜻하는 규칙도 있으니, 분류명과 취지를 보고 실제로 위반인지 최종 판단하세요. ");
        sb.append("위반 패턴에 해당하고 [예외 사항]에 해당하지 않으면 MATCHED, ");
        sb.append("위반이 아니면 NOT_MATCHED, 판단이 애매하거나 근거 자료가 부족하면 REVIEW_REQUIRED로 답하세요.\n");
        sb.append("status는 다음 중 정확히 하나: ").append(STATUS_VALUES).append(".\n");
        sb.append("reasonCode는 다음 중 정확히 하나: ").append(REASON_CODE_VALUES).append(".\n");
        sb.append("reason에는 판단 근거를 한국어 한두 문장으로 쓰세요. 추측하지 말고, 모르면 REVIEW_REQUIRED를 쓰세요.\n\n");

        sb.append("반드시 아래 JSON 형식으로만 응답하세요. 다른 설명은 붙이지 마세요.\n");
        sb.append("{\"status\": \"MATCHED\" 또는 \"NOT_MATCHED\" 또는 \"REVIEW_REQUIRED\", ");
        sb.append("\"reasonCode\": \"...\", \"reason\": \"판단 근거\"}\n");

        return sb.toString();
    }

    private RuleEvaluation parseResponse(String rawJson) {
        try {
            RawJudgment judgment = objectMapper.readValue(rawJson, RawJudgment.class);
            RuleEvaluation.Status status = parseStatus(judgment.status());
            RuleEvaluation.ReasonCode reasonCode = parseReasonCode(judgment.reasonCode());
            if (status == null || reasonCode == null) {
                log.warn("Rule Judge 응답에 알 수 없는 status/reasonCode: {}", rawJson);
                return new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED,
                        "AI 응답을 해석할 수 없어 확인이 필요합니다.");
            }
            String reason = isNotBlank(judgment.reason()) ? judgment.reason() : "판정 근거가 제공되지 않았습니다.";
            return new RuleEvaluation(status, reasonCode, reason);
        } catch (JacksonException e) {
            log.warn("Rule Judge 응답 JSON 파싱 실패: {}", e.getMessage());
            return new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED,
                    "AI 응답 파싱에 실패해 확인이 필요합니다.");
        }
    }

    private static RuleEvaluation.Status parseStatus(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return RuleEvaluation.Status.valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static RuleEvaluation.ReasonCode parseReasonCode(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return RuleEvaluation.ReasonCode.valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Gemini 응답 JSON 그대로의 모양 — 모르는 필드가 있어도 깨지지 않도록 ignoreUnknown. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawJudgment(String status, String reasonCode, String reason) {
    }
}
