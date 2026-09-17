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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
 * (P03_DISEASE_GUT, M02_LIVER_MARKER, S02_BODY_AREA, L02_UV) + 원래 리터럴형이었으나
 * 정규식으로는 예외 판단이 불가해 이관한 2개(C22_SUPERLATIVE, C30_NATURAL_FREE) = 39개를
 * 대상으로 하는 AI 기반 {@link RuleEvaluator} 초안 — "직접·암시 주장인지", "확장하는지" 같은
 * 매번 새 문장을 해석해야 하는 규칙군(자세한 분류 근거는 pipeline 아티팩트 ④단계 참고)을 다룬다.
 * 리터럴형(7개)과 혼합 중 예외가 텍스트만으로 판단 가능한 2개(M03_ALCOHOL, S01_PAIN)는
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
 * <p><b>RAG grounding</b>: {@link RuleGroundingProvider}가 이 Rule에 매핑된 근거 문서
 * (rule_sources) 범위 안에서 Claim과 관련도 높은 원문 문단을 찾아오면, 프롬프트에 [근거 문서
 * 원문] 섹션으로 포함시킨다 — 특히 {@code rules_v0.1.csv}의 예외 조건이 요약돼 있어 판단이
 * 애매한 규칙(예: 예외가 많은 규칙군)에서 실제 출처 원문을 근거로 판단하게 하려는 목적. 조회
 * 실패/매핑 없음이면 조용히 생략되고 기존 프롬프트로 폴백한다({@link RagRuleGroundingProvider}
 * 참고) — grounding은 판정을 더 정확하게 하는 보조 수단이지, 없으면 판정이 막히는 필수
 * 전제조건이 아니다.
 *
 * <p><b>배치는 시도했다가 보류했다(2026-09-17)</b>: Claim 1건에 여러 규칙을 한 프롬프트에
 * 묶어 JSON 배열로 받는 {@code evaluateBatch} 오버라이드를 구현하고, 같은 Claim·같은
 * 규칙 8개를 배치 1회 vs 개별 8회로 각각 돌려 결과가 일치하는지 실측했다 — 일치율이
 * 54~67%에 그쳤고(needsOutsideContext 게이트가 배치에서 방향 없이 흔들림), 프롬프트
 * 구조 개선·청크 축소(3개)·모델 교체(gemini-3.5-flash) 세 가지를 다 시도해도 크게
 * 나아지지 않았다. 배치를 포기하고 {@link RuleEvaluator#evaluateBatch}의 기본 구현
 * (규칙마다 {@link #evaluate}를 그대로 호출)을 그대로 쓰기로 결정 — 정확도(검증
 * 데이터셋 75.2%, REVIEW_REQUIRED 56%)를 지키는 쪽을 택했다. 그 대신 Claim 1건당
 * 적용 규칙 수(COMMON 12 + 원료별 매핑분)만큼 호출이 그대로 나가는데, 이 호출 수 자체를
 * 줄이는 방안(예: 사전 키워드 필터로 명백히 무관한 COMMON 규칙 스킵)은 별도 과제로
 * 미뤘다 — pipeline 아티팩트 로드맵 참고.
 */
@Component
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
            "P03_DISEASE_GUT", "M02_LIVER_MARKER", "S02_BODY_AREA", "L02_UV",
            // 원래 리터럴형이었으나 예외 판단이 정규식으로 확정 불가해(항상 REVIEW_REQUIRED) 이관한 2개
            "C22_SUPERLATIVE", "C30_NATURAL_FREE"
    );

    private static final String STATUS_VALUES = "MATCHED, NOT_MATCHED, REVIEW_REQUIRED";
    private static final String REASON_CODE_VALUES =
            "SUPPORTED_CONDITION_CONFIRMED, CONDITION_NOT_MET, EXCEPTION_CONFIRMED, "
                    + "SEMANTIC_COMPARISON_REQUIRED, OFFICIAL_FUNCTION_DATA_INCOMPLETE, "
                    + "OFFICIAL_FUNCTION_EXACT_MATCH, OUTSIDE_SUPPORTED_LANGUAGE";

    private final GeminiClient geminiClient;
    private final RuleGroundingProvider groundingProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Spring이 쓰는 생성자 — 판정용 grounding은 OFF가 팀 결정(2026-09-17)이라
     * {@link RuleGroundingProvider} 빈이 없어도(현재 {@code @Component} 없음) 기동되도록
     * {@code groundingProvider=null}로 고정한다. grounding을 다시 켜기로 하면 이 생성자를
     * 지우고 아래 2-인자 생성자에 {@code @Autowired}를 옮기면 된다.
     */
    @Autowired
    public AiRuleEvaluator(GeminiClient geminiClient) {
        this(geminiClient, null);
    }

    public AiRuleEvaluator(GeminiClient geminiClient, RuleGroundingProvider groundingProvider) {
        this.geminiClient = geminiClient;
        this.groundingProvider = groundingProvider;
    }

    @Override
    public Set<String> ruleCodes() {
        return TARGET_RULE_CODES;
    }

    @Override
    public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
        RuleEvaluation shortCircuit = precheckShortCircuit(request.claim());
        if (shortCircuit != null) {
            return shortCircuit;
        }
        String prompt = buildPrompt(rule, request);
        String rawResponse = geminiClient.generate(prompt, true);
        return parseResponse(rawResponse);
    }

    /** claim 텍스트 없음/문맥 미확인/제품과 무관 케이스는 규칙과 무관하게 곧바로 결정됨 — AI 호출 전 공통 필터. */
    private RuleEvaluation precheckShortCircuit(RuleAnalysisRequest.Claim claim) {
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
        return null;
    }

    private String buildPrompt(Rule rule, RuleAnalysisRequest request) {
        RuleAnalysisRequest.Claim claim = request.claim();
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 건강기능식품 광고 문구가 특정 규칙에 해당하는지 판정하는 검수 도구입니다.\n\n");

        sb.append("[판단 기준]\n");
        sb.append("분류: ").append(rule.getJudgmentCategory()).append('\n');
        sb.append("적용 조건: ").append(rule.getApplicationConditions()).append('\n');
        sb.append("(주의: 위 조건에 나온 핵심 단어가 문장에 있다는 사실만으로 자동 충족되는 게 아닙니다 — ");
        sb.append("문장이 실제로 구체적인 효능 확장·결합 주장을 담고 있는지 애매하면, 아래 needsOutsideContext를 ");
        sb.append("반드시 true로 답하세요. 막연한 상황 묘사·부드러운 동기부여 문구·단순 증상 언급은 핵심 단어가 ");
        sb.append("있어도 대부분 애매하거나 위반이 아닙니다.)\n");
        if (isNotBlank(rule.getExceptions())) {
            sb.append("예외 사항: ").append(rule.getExceptions()).append('\n');
            if (rule.getExceptions().contains("별도")) {
                sb.append("(주의: 위 예외 사항에 있는 \"별도\"라는 표현은 사람이 직접 재검토해야 한다는 뜻으로 ");
                sb.append("적어둔 메모입니다 — 완전한 판단 기준이 아닙니다. 이 Claim이 그 키워드·상황과 관련 ");
                sb.append("있어 보이면, 스스로 위반/정상 여부를 판단하지 말고 아래 needsOutsideContext를 ");
                sb.append("반드시 true로 답해 REVIEW_REQUIRED로 넘기세요.)\n");
            }
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

        List<String> groundingTexts = groundingProvider != null
                ? groundingProvider.groundingTexts(rule, claim.text())
                : List.of();
        if (!groundingTexts.isEmpty()) {
            sb.append("[근거 문서 원문] (이 규칙의 실제 출처 문서에서 이 Claim과 관련도가 높은 문단만 발췌 — ")
                    .append("참고용이며 전체 문서가 아니므로, 여기 없다고 해서 예외/적용조건이 없는 것은 아님)\n");
            for (String text : groundingTexts) {
                sb.append("- ").append(text).append('\n');
            }
            sb.append('\n');
        }

        sb.append("MATCHED는 \"이 Claim이 분류(").append(rule.getJudgmentCategory())
                .append(")가 우려하는 광고 위반 패턴에 실제로 해당한다\"는 뜻입니다. ");
        sb.append("[적용 조건]은 그 위반 여부를 판단하는 기준일 뿐, 조건 문장이 문자 그대로 참이라고 해서 ");
        sb.append("무조건 MATCHED가 되는 건 아닙니다 — 조건을 충족하는 것 자체가 오히려 정상적인 표시(위반 아님)를 ");
        sb.append("뜻하는 규칙도 있으니, 분류명과 취지를 보고 실제로 위반인지 최종 판단하세요. ");
        sb.append("위반 패턴에 해당하고 [예외 사항]에 해당하지 않으면 MATCHED, ");
        sb.append("위반이 아니면 NOT_MATCHED로 답하세요.\n\n");

        sb.append("REVIEW_REQUIRED는 실패나 회피가 아니라, 문장만으로는 확정할 수 없을 때 내려야 하는 ");
        sb.append("올바른 판단입니다 — 억지로 MATCHED/NOT_MATCHED 중 하나를 고르지 마세요. 다음 중 하나라도 ");
        sb.append("해당하면 REVIEW_REQUIRED를 선택하세요: ");
        sb.append("(1) 이 문장이 제품 효과를 암시하는지 단순 정보 제공인지 해석이 갈릴 수 있음, ");
        sb.append("(2) 위반/정상 여부가 문장 밖의 정황(전체 광고 맥락, 이미지, 실제 데이터)에 달려 있어 ");
        sb.append("이 문장만으로는 그 정황을 알 수 없음, ");
        sb.append("(3) 위에 근거 문서 원문이 제공돼 있어도 그 문서는 일반적 기준일 뿐 이 Claim의 구체적 정황(예: 실제 시점·비교대상·수치의 진위)까지 ");
        sb.append("확인해주지는 않음 — 근거 문서가 있다는 것과 이 Claim이 확정적이라는 것은 별개입니다.\n");
        sb.append("답하기 전에 먼저 needsOutsideContext를 판단하세요: 이 Claim의 위반 여부를 확정하려면 ");
        sb.append("문장 밖 정보 — 광고 전체 레이아웃·구획, 함께 실린 이미지, 이 제품의 실제 원료·인정 기능성 데이터, ");
        sb.append("인용의 출처·시점, 수치의 실제 근거 자료 — 를 봐야 합니까?\n");
        sb.append("- true: 문장 밖 정보 없이는 확정할 수 없다 → status는 반드시 REVIEW_REQUIRED\n");
        sb.append("- false: 이 문장 자체가 위반인지 아닌지를 명확히 드러낸다 → MATCHED 또는 NOT_MATCHED\n");
        sb.append("막연한 표현(무엇을 어떻게 한다는 게 특정되지 않은 문구), 질문·권유형 문구, 느낌·기분 표현, ");
        sb.append("대상·상황만 언급하고 효과는 말하지 않는 문구는 대부분 true입니다.\n");
        sb.append("주의: 문장 안에 \"자체 조사 결과\", 구체적 수치, \"연구에서\" 같은 데이터·연구 언급이 ");
        sb.append("있다는 것 자체는 문장 밖 정보가 필요하다는 신호가 아닙니다 — 오히려 그런 언급 자체가 근거 ");
        sb.append("없는 자체 주장임을 드러내는 위반 신호일 수 있어 MATCHED에 가까울 수 있습니다. ");
        sb.append("\"이 수치·연구가 진짜인지 검증이 필요하다\"는 이유만으로 true를 고르지 마세요 — 그 기준이면 ");
        sb.append("모든 광고 문구가 항상 애매해집니다. needsOutsideContext=true는 이 문장 자체의 의미·의도가 ");
        sb.append("여러 갈래로 해석될 때만 쓰세요.\n");
        sb.append("status는 다음 중 정확히 하나: ").append(STATUS_VALUES).append(".\n");
        sb.append("reasonCode는 다음 중 정확히 하나: ").append(REASON_CODE_VALUES).append(".\n");
        sb.append("reason에는 판단 근거를 한국어 한두 문장으로 쓰세요. 추측하지 말고, 모르면 REVIEW_REQUIRED를 쓰세요.\n\n");

        sb.append("반드시 아래 JSON 형식으로만 응답하세요. 다른 설명은 붙이지 마세요.\n");
        sb.append("{\"needsOutsideContext\": true 또는 false, ");
        sb.append("\"status\": \"MATCHED\" 또는 \"NOT_MATCHED\" 또는 \"REVIEW_REQUIRED\", ");
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
