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
 * <p><b>등록돼 있으나 전체가 켜진 건 아니다</b>: {@code @Component}로 등록되고
 * {@code RuleEvaluatorRegistry}가 잡아가지만, 실제로 판정이 나가는 것은
 * {@code RuleJudgeProperties}의 allowlist에 있는 규칙뿐이다 — 이 클래스가 선언한 39개 중
 * 20개(2026-09-17 기준). 검증된 것부터 단계적으로 넓히려는 구조이고, allowlist 밖의 코드는
 * 평가기가 있어도 {@code UNSUPPORTED_RULE}로 남는다.
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
        if (rule.getApplicationConditions() != null && rule.getApplicationConditions().contains("구분")) {
            // C04_DISEASE_INFO_LINK 실측에서, 문장에 이미 "---" 같은 구분 기호나 "출처: OOO" 같은
            // 명시적 인용이 있는데도 모델이 이걸 "구분됐다"는 신호로 안 쓰고 그냥 MATCHED로 확정하는
            // 것이 반복 관찰됐다(3회 중 2회). 적용 조건 자체가 "구분됐는지"를 묻고 있으니, 그 구분을
            // 알아볼 수 있는 텍스트 신호를 명시해준다.
            sb.append("(주의: 문장 안에 구분 기호(예: \"---\", 줄바꿈, 괄호)나 \"출처: OOO\" 같은 명시적 ");
            sb.append("인용이 있으면, 그것 자체가 \"명확히 구분됐다\"는 강한 신호입니다 — 구분 기호가 ");
            sb.append("있는데도 질병 정보와 제품 광고가 섞여 있다고 보려면, 그 구분을 무효화할 만한 다른 ");
            sb.append("근거(예: 구분 직후 곧바로 질병명과 제품을 같은 문장에서 연결)가 있어야 합니다.)\n");
        }
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
        if ("C21_UNFAIR_COMPARISON".equals(rule.getRuleCode())) {
            // 실제 심의 사례(ad_cases BAD-02)를 그대로 예시로 넣는다 — 일반 지침("핵심 단어만으로
            // 판단하지 마세요" 등)은 C14/C21 등 여러 규칙에서 두 번 시도해도 전혀 안 먹혔다(문서
            // 참고). C21의 candidateExamples가 "함량 n배이므로 효과 n배" 같은 명시적 수치 비교만
            // 담고 있어서, 모델이 "명시적 비교 문구가 없으면 해당 없음"으로 좁게 해석하는 것으로
            // 보인다 — 실제로는 인증·안전성 표시를 나열하는 것만으로도 암묵적 우월 주장이 될 수
            // 있는데, 그 유형이 candidateExamples에 아예 없다. 지침 대신 실제 사례를 보여준다.
            sb.append("(실제 사례: \"two safe 인증 / 임산부 안전평가 인증 완료\"라는 문구는 ");
            sb.append("\"~보다\", \"n배\" 같은 명시적 비교 표현이 전혀 없지만, 실제 심의에서 ");
            sb.append("위반(MATCHED)으로 판정됐습니다 — 사유: \"자사 제품만 더 안전하고 우수한 ");
            sb.append("것으로 오인할 우려\". 인증·안전성 표시를 여러 개 나열하는 것 자체가, ");
            sb.append("비교 대상을 직접 언급하지 않고도 \"우리 제품만 특별히 안전·우수하다\"는 ");
            sb.append("암묵적 비교·우월 주장이 될 수 있습니다. 명시적 비교 문구가 없다고 해서 ");
            sb.append("이 규칙이 적용되지 않는다고 단정하지 마세요.\n");
            // 반복 측정(배치 18/24, 개별 6/9)에서 "다른 제품보다 흡수가 잘 되는 이유가 있습니다"
            // (REVIEW_REQUIRED 기대)가 매번 MATCHED로 틀렸다 — BAD-02 예시는 "비교 대상 없이
            // 단정"하는 유형이라 이 케이스(비교 우위를 주장하되 근거 유무가 불명확한 유형)를
            // 못 덮는다. REVIEW-02 p.57~58 "하. 흡수율 및 생체이용률에 대한 표현" 원문을
            // 실제 근거로 추가한다 — 지어낸 예시가 아니라 C21의 참조 출처(REVIEW-02)에 이미
            // 있는 조항.
            sb.append("(심의기준 원문(REVIEW-02, \"하. 흡수율 및 생체이용률에 대한 표현\"): ");
            sb.append("\"과학적 근거 없이 흡수율 또는 생체이용률이 높은 제품으로 광고하는 것은 ");
            sb.append("소비자로 하여금 기능성이 우수한 제품으로 오인할 우려가 있으므로, 과학적으로 ");
            sb.append("입증된 객관적 사실인 경우에만 표현할 수 있다 — SCIE 등재 학술지 또는 이와 ");
            sb.append("동등한 인체시험 자료여야 하며 제품에 함유된 원료와 동일한 원료일 때만 가능\". ");
            sb.append("즉 흡수율·생체이용률 우위 주장은 그 자체로 자동 위반(MATCHED)이 아니라, ");
            sb.append("SCIE급 근거가 있으면 허용되고 없으면 위반인 조건부 규칙입니다. Claim 문구만으로는 ");
            sb.append("그 근거가 실제로 있는지 알 수 없다면(문구가 근거를 제시하지도, 명백히 안 ");
            sb.append("된다고 단정할 근거도 없다면) MATCHED로 단정하지 말고 needsOutsideContext를 ");
            sb.append("true로 답해 REVIEW_REQUIRED로 넘기세요.)\n");
        }
        if (isNotBlank(rule.getRequiredEvidence())) {
            sb.append("필요 근거(참고용 — 지금 판단엔 이 근거 자료가 없을 수 있음): ")
                    .append(rule.getRequiredEvidence()).append('\n');
            // 여기 "실증자료"처럼 증빙을 요구하는 단어가 들어 있으면, 모델이 그 자료를 지금
            // 볼 수 없다는 이유로 needsOutsideContext=true를 골라 전부 REVIEW_REQUIRED로
            // 흘려보내는 일이 관찰됐다(C22 3회 내내). 아래 지침에 일반 경고가 있지만 규칙
            // 텍스트가 더 가깝고 구체적이라 밀린다 — 같은 자리에서 한 번 더 막는다.
            sb.append("(주의: 여기 적힌 자료를 지금 볼 수 없다는 사실 자체는 needsOutsideContext를 ");
            sb.append("true로 만들 근거가 아닙니다. 광고 문구가 그 근거를 제시하지 않은 채 단정한다면 ");
            sb.append("오히려 위반 신호이고, 반대로 문구 안에 비교 기준·시점·범위가 이미 명시돼 ");
            sb.append("있다면 그 내용의 진위를 외부에서 확인할 수 없다는 이유만으로 판단을 미루지 마세요. ");
            sb.append("다만 이건 \"자료의 진위\"에만 해당합니다 — 문구 자체의 의미가 여러 갈래로 읽혀서 ");
            sb.append("무엇을 주장하는지 확정할 수 없는 경우는 별개이고, 그때는 true가 맞습니다.)\n");
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
            return toEvaluation(judgment, rawJson);
        } catch (JacksonException e) {
            log.warn("Rule Judge 응답 JSON 파싱 실패: {}", e.getMessage());
            return new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED,
                    "AI 응답 파싱에 실패해 확인이 필요합니다.");
        }
    }

    private RuleEvaluation toEvaluation(RawJudgment judgment, String rawJsonForLogging) {
        RuleEvaluation.Status status = parseStatus(judgment.status());
        if (status == null) {
            log.warn("Rule Judge 응답의 status를 해석할 수 없음: {}", rawJsonForLogging);
            return new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED,
                    "AI 응답을 해석할 수 없어 확인이 필요합니다.");
        }
        String reason = isNotBlank(judgment.reason()) ? judgment.reason() : "판정 근거가 제공되지 않았습니다.";

        // 프롬프트는 "needsOutsideContext=true면 status는 반드시 REVIEW_REQUIRED"라고 지시하지만,
        // 지금까지 그 약속은 모델이 지켜주기만 바라는 상태였다 — 이 필드를 파싱조차 하지 않아
        // 어겨도 그대로 통과했다. 어긋나는 방향이 NOT_MATCHED면 Finding이 아예 만들어지지 않아
        // 사용자 화면에서 흔적 없이 사라지므로(실측: R02_MENOPAUSE), 게이트는 프롬프트가 아니라
        // 여기서 강제한다.
        if (Boolean.TRUE.equals(judgment.needsOutsideContext()) && status != REVIEW_REQUIRED) {
            log.warn("Rule Judge가 needsOutsideContext=true인데 status={} 를 반환 — REVIEW_REQUIRED로 보정함: {}",
                    status, rawJsonForLogging);
            return new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED, reason);
        }

        RuleEvaluation.ReasonCode reasonCode = parseReasonCode(judgment.reasonCode());
        if (reasonCode == null) {
            // 모델이 목록에 없는 코드를 지어내는 경우가 실제로 관찰됐다(예: CONDITION_NOT_MET
            // 대신 "CONDITION_NOT_MATCHED"). 판정(status) 자체는 멀쩡한데 코드 이름 하나 때문에
            // 판정 전체를 버리면 멀쩡한 결과가 REVIEW_REQUIRED로 둔갑하므로, status에 맞는
            // 기본 코드로 보정하고 로그만 남긴다.
            reasonCode = defaultReasonCodeFor(status);
            log.warn("Rule Judge 응답의 reasonCode '{}'를 알 수 없어 {}로 보정함", judgment.reasonCode(), reasonCode);
        }
        return new RuleEvaluation(status, reasonCode, reason);
    }

    private static RuleEvaluation.ReasonCode defaultReasonCodeFor(RuleEvaluation.Status status) {
        return switch (status) {
            case MATCHED -> RuleEvaluation.ReasonCode.SUPPORTED_CONDITION_CONFIRMED;
            case NOT_MATCHED -> RuleEvaluation.ReasonCode.CONDITION_NOT_MET;
            case REVIEW_REQUIRED -> SEMANTIC_COMPARISON_REQUIRED;
        };
    }

    /**
     * 같은 규칙을 여러 Claim에 한 프롬프트로 묶어 호출 수를 줄인다. 이전에 실패한 "Claim 1개 +
     * 규칙 여러 개" 배치(needsOutsideContext 게이트가 방향 없이 흔들려 일치율 54~67%)와는 반대
     * 축이다 — 여기서는 [판단 기준]이 배치 전체에서 동일해서 그 혼선이 구조적으로 없고, 검증
     * 데이터셋 실측(1차 파일럿 9개 규칙 × claim 3건 = 27건)에서 개별 호출과 25/27(92.6%)이 일치했다.
     *
     * <p>다만 공짜는 아니다: 개별 호출에서 3회 반복 9/9로 완벽하던 B02_VIRUS·R02_MENOPAUSE의
     * 특정 claim이 배치에서는 3회 내내 다르게 판정됐다. <b>둘의 성격은 다르다</b> — B02는
     * {@code MATCHED → REVIEW_REQUIRED}라 경고가 약해지는 정도지만, R02는
     * {@code REVIEW_REQUIRED → NOT_MATCHED}라 Finding 자체가 사라져 화면에 아무것도 남지 않는다.
     * 같은 프롬프트 안의 다른 claim이 판단에 영향을 주는 것으로 추정된다. 참고로 배치로 검증한
     * 것은 allowlist의 AI 규칙 20개 중 9개뿐이고, 나머지 11개는 아직 측정하지 않았다.
     *
     * @return {@code requests}와 같은 순서·크기의 결과 리스트.
     */
    @Override
    public List<RuleEvaluation> evaluateAcrossClaims(Rule rule, List<RuleAnalysisRequest> requests) {
        List<RuleEvaluation> results = new java.util.ArrayList<>(java.util.Collections.nCopies(requests.size(), null));
        List<Integer> pendingIndices = new java.util.ArrayList<>();
        List<RuleAnalysisRequest> pendingRequests = new java.util.ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            RuleEvaluation shortCircuit = precheckShortCircuit(requests.get(i).claim());
            if (shortCircuit != null) {
                results.set(i, shortCircuit);
            } else {
                pendingIndices.add(i);
                pendingRequests.add(requests.get(i));
            }
        }
        if (!pendingRequests.isEmpty()) {
            String prompt = buildBatchPromptForRule(rule, pendingRequests);
            String rawResponse = geminiClient.generate(prompt, true);
            List<RuleEvaluation> batchResults = parseBatchResponse(rawResponse, pendingRequests.size());
            for (int i = 0; i < pendingIndices.size(); i++) {
                results.set(pendingIndices.get(i), batchResults.get(i));
            }
        }
        return results;
    }

    private String buildBatchPromptForRule(Rule rule, List<RuleAnalysisRequest> requests) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 건강기능식품 광고 문구가 특정 규칙에 해당하는지 판정하는 검수 도구입니다.\n");
        sb.append("아래 [판단 기준]은 이어지는 모든 Claim에 공통으로 적용됩니다 — Claim마다 서로 ");
        sb.append("독립적으로 판단하되(다른 Claim의 판단이 이 Claim에 영향을 주면 안 됨), 판단 기준은 하나입니다.\n\n");

        sb.append("[판단 기준]\n");
        sb.append("분류: ").append(rule.getJudgmentCategory()).append('\n');
        sb.append("적용 조건: ").append(rule.getApplicationConditions()).append('\n');
        if (rule.getApplicationConditions() != null && rule.getApplicationConditions().contains("구분")) {
            // C04_DISEASE_INFO_LINK 실측에서, 문장에 이미 "---" 같은 구분 기호나 "출처: OOO" 같은
            // 명시적 인용이 있는데도 모델이 이걸 "구분됐다"는 신호로 안 쓰고 그냥 MATCHED로 확정하는
            // 것이 반복 관찰됐다(3회 중 2회). 적용 조건 자체가 "구분됐는지"를 묻고 있으니, 그 구분을
            // 알아볼 수 있는 텍스트 신호를 명시해준다.
            sb.append("(주의: 문장 안에 구분 기호(예: \"---\", 줄바꿈, 괄호)나 \"출처: OOO\" 같은 명시적 ");
            sb.append("인용이 있으면, 그것 자체가 \"명확히 구분됐다\"는 강한 신호입니다 — 구분 기호가 ");
            sb.append("있는데도 질병 정보와 제품 광고가 섞여 있다고 보려면, 그 구분을 무효화할 만한 다른 ");
            sb.append("근거(예: 구분 직후 곧바로 질병명과 제품을 같은 문장에서 연결)가 있어야 합니다.)\n");
        }
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
        if ("C21_UNFAIR_COMPARISON".equals(rule.getRuleCode())) {
            // 실제 심의 사례(ad_cases BAD-02)를 그대로 예시로 넣는다 — 일반 지침("핵심 단어만으로
            // 판단하지 마세요" 등)은 C14/C21 등 여러 규칙에서 두 번 시도해도 전혀 안 먹혔다(문서
            // 참고). C21의 candidateExamples가 "함량 n배이므로 효과 n배" 같은 명시적 수치 비교만
            // 담고 있어서, 모델이 "명시적 비교 문구가 없으면 해당 없음"으로 좁게 해석하는 것으로
            // 보인다 — 실제로는 인증·안전성 표시를 나열하는 것만으로도 암묵적 우월 주장이 될 수
            // 있는데, 그 유형이 candidateExamples에 아예 없다. 지침 대신 실제 사례를 보여준다.
            sb.append("(실제 사례: \"two safe 인증 / 임산부 안전평가 인증 완료\"라는 문구는 ");
            sb.append("\"~보다\", \"n배\" 같은 명시적 비교 표현이 전혀 없지만, 실제 심의에서 ");
            sb.append("위반(MATCHED)으로 판정됐습니다 — 사유: \"자사 제품만 더 안전하고 우수한 ");
            sb.append("것으로 오인할 우려\". 인증·안전성 표시를 여러 개 나열하는 것 자체가, ");
            sb.append("비교 대상을 직접 언급하지 않고도 \"우리 제품만 특별히 안전·우수하다\"는 ");
            sb.append("암묵적 비교·우월 주장이 될 수 있습니다. 명시적 비교 문구가 없다고 해서 ");
            sb.append("이 규칙이 적용되지 않는다고 단정하지 마세요.\n");
            // 반복 측정(배치 18/24, 개별 6/9)에서 "다른 제품보다 흡수가 잘 되는 이유가 있습니다"
            // (REVIEW_REQUIRED 기대)가 매번 MATCHED로 틀렸다 — BAD-02 예시는 "비교 대상 없이
            // 단정"하는 유형이라 이 케이스(비교 우위를 주장하되 근거 유무가 불명확한 유형)를
            // 못 덮는다. REVIEW-02 p.57~58 "하. 흡수율 및 생체이용률에 대한 표현" 원문을
            // 실제 근거로 추가한다 — 지어낸 예시가 아니라 C21의 참조 출처(REVIEW-02)에 이미
            // 있는 조항.
            sb.append("(심의기준 원문(REVIEW-02, \"하. 흡수율 및 생체이용률에 대한 표현\"): ");
            sb.append("\"과학적 근거 없이 흡수율 또는 생체이용률이 높은 제품으로 광고하는 것은 ");
            sb.append("소비자로 하여금 기능성이 우수한 제품으로 오인할 우려가 있으므로, 과학적으로 ");
            sb.append("입증된 객관적 사실인 경우에만 표현할 수 있다 — SCIE 등재 학술지 또는 이와 ");
            sb.append("동등한 인체시험 자료여야 하며 제품에 함유된 원료와 동일한 원료일 때만 가능\". ");
            sb.append("즉 흡수율·생체이용률 우위 주장은 그 자체로 자동 위반(MATCHED)이 아니라, ");
            sb.append("SCIE급 근거가 있으면 허용되고 없으면 위반인 조건부 규칙입니다. Claim 문구만으로는 ");
            sb.append("그 근거가 실제로 있는지 알 수 없다면(문구가 근거를 제시하지도, 명백히 안 ");
            sb.append("된다고 단정할 근거도 없다면) MATCHED로 단정하지 말고 needsOutsideContext를 ");
            sb.append("true로 답해 REVIEW_REQUIRED로 넘기세요.)\n");
        }
        if (isNotBlank(rule.getRequiredEvidence())) {
            sb.append("필요 근거(참고용 — 지금 판단엔 이 근거 자료가 없을 수 있음): ")
                    .append(rule.getRequiredEvidence()).append('\n');
            // 여기 "실증자료"처럼 증빙을 요구하는 단어가 들어 있으면, 모델이 그 자료를 지금
            // 볼 수 없다는 이유로 needsOutsideContext=true를 골라 전부 REVIEW_REQUIRED로
            // 흘려보내는 일이 관찰됐다(C22 3회 내내). 아래 지침에 일반 경고가 있지만 규칙
            // 텍스트가 더 가깝고 구체적이라 밀린다 — 같은 자리에서 한 번 더 막는다.
            sb.append("(주의: 여기 적힌 자료를 지금 볼 수 없다는 사실 자체는 needsOutsideContext를 ");
            sb.append("true로 만들 근거가 아닙니다. 광고 문구가 그 근거를 제시하지 않은 채 단정한다면 ");
            sb.append("오히려 위반 신호이고, 반대로 문구 안에 비교 기준·시점·범위가 이미 명시돼 ");
            sb.append("있다면 그 내용의 진위를 외부에서 확인할 수 없다는 이유만으로 판단을 미루지 마세요. ");
            sb.append("다만 이건 \"자료의 진위\"에만 해당합니다 — 문구 자체의 의미가 여러 갈래로 읽혀서 ");
            sb.append("무엇을 주장하는지 확정할 수 없는 경우는 별개이고, 그때는 true가 맞습니다.)\n");
        }
        sb.append('\n');

        List<RuleOfficialFunctionContext> officialFunctions = requests.get(0).officialFunctions().values();
        if (!officialFunctions.isEmpty()) {
            sb.append("[확정된 공식 기능성] (참고용 — 이 성분들에 실제로 인정된 기능성, 아래 모든 Claim에 공통)\n");
            for (RuleOfficialFunctionContext fn : officialFunctions) {
                sb.append("- ").append(fn.canonicalName()).append(": ").append(fn.officialFunctionText()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("[판단 대상 Claim 목록] — 총 ").append(requests.size()).append("건, 각각 독립적으로 판단하세요\n");
        for (int i = 0; i < requests.size(); i++) {
            RuleAnalysisRequest.Claim claim = requests.get(i).claim();
            sb.append(i + 1).append(". 문장: \"").append(claim.text()).append("\" | 문맥: ")
                    .append(claim.context()).append(" (근거: ").append(claim.contextEvidence()).append(")\n");
            List<RiskSignalContext> relatedSignals = requests.get(i).riskSignals().stream()
                    .filter(rs -> rs.relatesTo(rule.getJudgmentCategory()))
                    .toList();
            for (RiskSignalContext rs : relatedSignals) {
                sb.append("   [AI#1이 미리 표시해둔 위험 신호] ").append(rs.signalType())
                        .append(": ").append(rs.text()).append('\n');
            }
        }
        sb.append('\n');

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
        sb.append("각 Claim마다 답하기 전에 먼저 needsOutsideContext를 판단하세요: 이 Claim의 위반 여부를 확정하려면 ");
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

        sb.append("반드시 아래 JSON 배열 형식으로만 응답하세요 — 배열 길이는 정확히 ").append(requests.size())
                .append("이고, 각 원소의 \"no\"에는 위 [판단 대상 Claim 목록]에서 그 Claim에 붙은 번호를 ")
                .append("그대로 적으세요(1부터 ").append(requests.size())
                .append("까지 하나씩, 빠지거나 겹치면 안 됩니다). ");
        sb.append("다른 설명은 붙이지 마세요.\n");
        sb.append("[{\"no\": 1, \"needsOutsideContext\": true 또는 false, ");
        sb.append("\"status\": \"MATCHED\" 또는 \"NOT_MATCHED\" 또는 \"REVIEW_REQUIRED\", ");
        sb.append("\"reasonCode\": \"...\", \"reason\": \"판단 근거\"}, ...]\n");

        return sb.toString();
    }

    private List<RuleEvaluation> parseBatchResponse(String rawJson, int expectedSize) {
        try {
            List<RawJudgment> judgments = objectMapper.readValue(
                    rawJson, new tools.jackson.core.type.TypeReference<List<RawJudgment>>() {
                    });
            if (judgments.size() != expectedSize) {
                log.warn("Rule Judge 배치 응답 개수 불일치: 기대 {}건, 실제 {}건 — {}",
                        expectedSize, judgments.size(), rawJson);
                return fallbackList(expectedSize, "AI 배치 응답 개수가 기대와 달라 확인이 필요합니다.");
            }
            return alignByClaimNo(judgments, expectedSize, rawJson);
        } catch (JacksonException e) {
            log.warn("Rule Judge 배치 응답 JSON 파싱 실패: {}", e.getMessage());
            return fallbackList(expectedSize, "AI 배치 응답 파싱에 실패해 확인이 필요합니다.");
        }
    }

    /**
     * 배치 응답을 Claim에 짝짓는다. 예전에는 응답 순서를 그대로 믿었는데, 응답에 Claim 식별자가
     * 없어서 모델이 순서를 바꿔도 개수만 맞으면 통과했다 — 그러면 "A 문장이 위반"이라는 판정이
     * B 문장에 붙는다. 이제 프롬프트가 매긴 번호를 응답에 되받아 그 번호로 맞춘다.
     *
     * <p>세 갈래로 나뉜다: 번호가 전부 제대로 오면 <b>번호로</b> 맞추고(순서가 바뀌어도 안전),
     * 번호가 아예 없으면 예전처럼 <b>순서로</b> 맞춘다(모델이 필드를 무시해도 기능이 죽지 않게).
     * 번호가 있는데 1..N을 정확히 한 번씩 덮지 못하면 — 빠지거나 겹치거나 범위를 벗어나면 —
     * 어느 판정이 어느 Claim 것인지 알 수 없으므로 <b>전부 확인 필요</b>로 돌린다. 잘못 짝지어진
     * 판정을 사용자에게 보여주느니 사람이 보게 하는 편이 낫다.
     */
    private List<RuleEvaluation> alignByClaimNo(List<RawJudgment> judgments, int expectedSize, String rawJson) {
        if (judgments.stream().noneMatch(judgment -> judgment.no() != null)) {
            log.warn("Rule Judge 배치 응답에 Claim 번호(no)가 없어 순서대로 짝지음 — {}", rawJson);
            return judgments.stream().map(judgment -> toEvaluation(judgment, rawJson)).toList();
        }
        RuleEvaluation[] aligned = new RuleEvaluation[expectedSize];
        for (RawJudgment judgment : judgments) {
            Integer no = judgment.no();
            if (no == null || no < 1 || no > expectedSize || aligned[no - 1] != null) {
                log.warn("Rule Judge 배치 응답의 Claim 번호가 1~{}을 한 번씩 덮지 않음(no={}) — "
                        + "짝을 신뢰할 수 없어 전부 확인 필요로 처리: {}", expectedSize, no, rawJson);
                return fallbackList(expectedSize, "AI 배치 응답의 Claim 번호가 어긋나 확인이 필요합니다.");
            }
            aligned[no - 1] = toEvaluation(judgment, rawJson);
        }
        return List.of(aligned);
    }

    private static List<RuleEvaluation> fallbackList(int size, String reason) {
        return java.util.Collections.nCopies(
                size, new RuleEvaluation(REVIEW_REQUIRED, SEMANTIC_COMPARISON_REQUIRED, reason));
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
    private record RawJudgment(Integer no, Boolean needsOutsideContext,
                               String status, String reasonCode, String reason) {
    }
}
