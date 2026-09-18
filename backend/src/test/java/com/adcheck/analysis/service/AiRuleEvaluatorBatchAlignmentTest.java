package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
import static com.adcheck.rule.service.RuleEvaluation.Status.MATCHED;
import static com.adcheck.rule.service.RuleEvaluation.Status.NOT_MATCHED;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델 응답을 판정으로 옮기는 과정에서 지켜야 할 두 가지를 검증한다.
 *
 * <p><b>①needsOutsideContext 게이트</b> — 프롬프트는 "문장 밖 정보가 필요하면 true, 그리고
 * true면 status는 반드시 REVIEW_REQUIRED"라고 지시한다. 그런데 이 필드를 파싱조차 하지 않던
 * 시절에는 모델이 약속을 어겨도 그대로 통과했다. 어긋나는 방향이 NOT_MATCHED면 Finding이
 * 만들어지지 않아 화면에서 흔적 없이 사라지므로(실측: R02_MENOPAUSE가 REVIEW_REQUIRED →
 * NOT_MATCHED로 새어나갔다), 게이트는 코드에서 강제해야 한다.
 *
 * <p><b>②Claim 번호 정렬</b> — 규칙 축 배치는 한 프롬프트에 Claim 여러 건을 넣고 JSON 배열을
 * 받는다. 예전에는 배열 순서를 그대로 믿었는데, 응답에 식별자가 없어 모델이 순서를 바꿔도
 * 개수만 맞으면 통과했다 — 그러면 "A 문장이 위반"이라는 판정이 B 문장에 붙는다.
 */
class AiRuleEvaluatorBatchAlignmentTest {

    private static final Rule RULE = CanonicalRuleFixture.rule("C01_DISEASE_PREVENTION");

    // ── ① needsOutsideContext 게이트 ────────────────────────────────────────────

    @Test
    void needsOutsideContext가_true인데_NOT_MATCHED로_오면_확인_필요로_바로잡는다() {
        RuleEvaluation result = evaluateSingle(judgment(null, true, "NOT_MATCHED", "CONDITION_NOT_MET", "위반 아님"));

        // 이 경로를 막지 않으면 Finding이 아예 안 만들어져 사용자가 잘못된 안심을 하게 된다.
        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.SEMANTIC_COMPARISON_REQUIRED);
        assertThat(result.reason()).isEqualTo("위반 아님");
    }

    @Test
    void needsOutsideContext가_true인데_MATCHED로_오면_확인_필요로_바로잡는다() {
        RuleEvaluation result = evaluateSingle(
                judgment(null, true, "MATCHED", "SUPPORTED_CONDITION_CONFIRMED", "질병 예방 주장"));

        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void needsOutsideContext가_false면_판정을_그대로_쓴다() {
        RuleEvaluation result = evaluateSingle(
                judgment(null, false, "MATCHED", "SUPPORTED_CONDITION_CONFIRMED", "질병 예방 주장"));

        assertThat(result.status()).isEqualTo(MATCHED);
        assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.SUPPORTED_CONDITION_CONFIRMED);
    }

    @Test
    void needsOutsideContext가_응답에_없으면_판정을_그대로_쓴다() {
        // 모델이 필드를 빠뜨렸다고 해서 멀쩡한 판정을 버리진 않는다 — 게이트는 명시적으로 true일 때만 건다.
        RuleEvaluation result = evaluateSingle(
                "{\"status\": \"MATCHED\", \"reasonCode\": \"SUPPORTED_CONDITION_CONFIRMED\", \"reason\": \"질병 예방 주장\"}");

        assertThat(result.status()).isEqualTo(MATCHED);
    }

    // ── ② Claim 번호 정렬 ──────────────────────────────────────────────────────

    @Test
    void 배치_응답의_순서가_뒤바뀌어_와도_Claim_번호대로_짝짓는다() {
        // 3번 → 1번 → 2번 순서로 뒤섞어 돌려준다. 순서를 그대로 믿으면 단언이 깨진다.
        String response = array(
                judgment(3, false, "REVIEW_REQUIRED", "SEMANTIC_COMPARISON_REQUIRED", "셋째 Claim"),
                judgment(1, false, "MATCHED", "SUPPORTED_CONDITION_CONFIRMED", "첫째 Claim"),
                judgment(2, false, "NOT_MATCHED", "CONDITION_NOT_MET", "둘째 Claim"));

        List<RuleEvaluation> results = evaluateBatch(response, 3);

        assertThat(results).extracting(RuleEvaluation::status)
                .containsExactly(MATCHED, NOT_MATCHED, REVIEW_REQUIRED);
        assertThat(results).extracting(RuleEvaluation::reason)
                .containsExactly("첫째 Claim", "둘째 Claim", "셋째 Claim");
    }

    @Test
    void 배치_응답의_Claim_번호가_겹치면_전부_확인_필요로_처리한다() {
        String response = array(
                judgment(1, false, "MATCHED", "SUPPORTED_CONDITION_CONFIRMED", "첫째"),
                judgment(1, false, "NOT_MATCHED", "CONDITION_NOT_MET", "또 첫째"),
                judgment(3, false, "NOT_MATCHED", "CONDITION_NOT_MET", "셋째"));

        List<RuleEvaluation> results = evaluateBatch(response, 3);

        // 2번 Claim의 판정이 없으므로 어느 것도 믿을 수 없다 — 사람이 보게 넘긴다.
        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(REVIEW_REQUIRED));
    }

    @Test
    void 배치_응답의_Claim_번호가_범위를_벗어나면_전부_확인_필요로_처리한다() {
        String response = array(
                judgment(1, false, "MATCHED", "SUPPORTED_CONDITION_CONFIRMED", "첫째"),
                judgment(2, false, "NOT_MATCHED", "CONDITION_NOT_MET", "둘째"),
                judgment(9, false, "NOT_MATCHED", "CONDITION_NOT_MET", "있지도 않은 9번"));

        List<RuleEvaluation> results = evaluateBatch(response, 3);

        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(REVIEW_REQUIRED));
    }

    @Test
    void 배치_응답에_Claim_번호가_아예_없으면_예전처럼_순서대로_짝짓는다() {
        // 모델이 새 필드를 무시하더라도 기능이 죽지 않아야 한다.
        String response = array(
                judgment(null, false, "MATCHED", "SUPPORTED_CONDITION_CONFIRMED", "첫째"),
                judgment(null, false, "NOT_MATCHED", "CONDITION_NOT_MET", "둘째"),
                judgment(null, false, "REVIEW_REQUIRED", "SEMANTIC_COMPARISON_REQUIRED", "셋째"));

        List<RuleEvaluation> results = evaluateBatch(response, 3);

        assertThat(results).extracting(RuleEvaluation::status)
                .containsExactly(MATCHED, NOT_MATCHED, REVIEW_REQUIRED);
    }

    @Test
    void 배치_프롬프트는_Claim_번호를_되받도록_요구한다() {
        CapturingGeminiClient geminiClient = new CapturingGeminiClient(array(
                judgment(1, false, "NOT_MATCHED", "CONDITION_NOT_MET", "첫째"),
                judgment(2, false, "NOT_MATCHED", "CONDITION_NOT_MET", "둘째")));

        new AiRuleEvaluator(geminiClient, null).evaluateAcrossClaims(RULE, requests(2));

        // 정렬이 동작하려면 프롬프트가 번호를 요구해야 한다 — 둘은 짝이라 같이 지킨다.
        assertThat(geminiClient.lastPrompt).contains("\"no\"");
        assertThat(geminiClient.lastPrompt).contains("빠지거나 겹치면 안 됩니다");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    private static RuleEvaluation evaluateSingle(String response) {
        return new AiRuleEvaluator(new CapturingGeminiClient(response), null)
                .evaluate(RULE, requests(1).getFirst());
    }

    private static List<RuleEvaluation> evaluateBatch(String response, int claimCount) {
        return new AiRuleEvaluator(new CapturingGeminiClient(response), null)
                .evaluateAcrossClaims(RULE, requests(claimCount));
    }

    private static List<RuleAnalysisRequest> requests(int count) {
        List<RuleAnalysisRequest> requests = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Claim claim = new Claim("claim-" + i, i + "번 문장입니다", PRODUCT_HEALTH_EFFECT_COPY, "본문 문단 확인됨");
            requests.add(new RuleAnalysisRequest(claim, List.of(), Set.of(),
                    new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false)));
        }
        return List.copyOf(requests);
    }

    /** {@code no}가 null이면 그 필드를 아예 빼고, needsOutsideContext가 null이면 그것도 뺀다. */
    private static String judgment(Integer no, Boolean needsOutsideContext,
                                   String status, String reasonCode, String reason) {
        StringBuilder sb = new StringBuilder("{");
        if (no != null) {
            sb.append("\"no\": ").append(no).append(", ");
        }
        if (needsOutsideContext != null) {
            sb.append("\"needsOutsideContext\": ").append(needsOutsideContext).append(", ");
        }
        sb.append("\"status\": \"").append(status).append("\", ");
        sb.append("\"reasonCode\": \"").append(reasonCode).append("\", ");
        sb.append("\"reason\": \"").append(reason).append("\"}");
        return sb.toString();
    }

    private static String array(String... judgments) {
        return "[" + String.join(", ", judgments) + "]";
    }

    /** 고정 응답을 돌려주면서 마지막으로 받은 프롬프트를 기록하는 대역. */
    private static final class CapturingGeminiClient extends GeminiClient {
        private final String response;
        String lastPrompt;

        CapturingGeminiClient(String response) {
            super("test-api-key", "test-model");
            this.response = response;
        }

        @Override
        String generate(String prompt, boolean jsonMode) {
            lastPrompt = prompt;
            return response;
        }
    }
}
