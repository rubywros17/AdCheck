package com.adcheck.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * AI #2(Comparison) 구현체 — Backend Contract(2026-09-10) 기준. Backend가 Product/
 * Ingredient/Official Function을 확정하고 RuleAnalysisService·RAG까지 마친 뒤 이 서비스를
 * 호출한다고 가정한다.
 *
 * <p>광고 Claim과 공식 인정 기능성의 의미 차이를 판단하고 소비자용 설명을 생성하는 것까지가
 * 역할이다 — 최종 법적 판정이나 Rule 재확정은 하지 않는다({@code ruleMatches}는 참고만 함).
 *
 * <p>RAG(evidence 검색)는 아직 구현되지 않아 호출 쪽에서 빈 리스트를 넘겨도 동작하도록
 * 만들었다 — evidence가 비어 있으면 그 부분 없이 비교만 수행한다.
 */
@Service
public class GeminiClaimComparisonService {

    private static final Logger log = LoggerFactory.getLogger(GeminiClaimComparisonService.class);

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public GeminiClaimComparisonService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
        this.objectMapper = new ObjectMapper();
    }

    public ClaimComparisonResult compare(ClaimComparisonRequest request) {
        String prompt = buildPrompt(request);
        String rawResponse = geminiClient.generate(prompt, true);
        return parseResponse(rawResponse);
    }

    private String buildPrompt(ClaimComparisonRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 건강기능식품 광고 표현이 공식 인정 기능성 범위를 벗어나는지 비교하는 검수 도구입니다.\n\n");

        sb.append("각 Claim에 대해 아래 [공식 인정 기능성]과 의미를 비교해서 comparisonStatus를 정하세요. ");
        sb.append("comparisonStatus는 다음 중 하나여야 합니다.\n");
        sb.append("- WITHIN_OFFICIAL_RANGE: 광고 표현이 공식 인정 기능성 범위 안에 있음\n");
        sb.append("- STRONGER_THAN_OFFICIAL: 광고 표현이 공식 인정 기능성보다 강하거나 다른 효과를 암시함\n");
        sb.append("- NO_OFFICIAL_BASIS: 이 제품/원료와 관련된 공식 인정 기능성을 찾을 수 없음\n");
        sb.append("- REVIEW_REQUIRED: 판단이 애매해서 사람 검토가 필요함\n\n");

        sb.append("- 최종 법적 위반 여부를 판정하지 마세요 — 의미 비교와 소비자용 설명 생성까지만 하면 됩니다.\n");
        sb.append("- ruleMatches가 있으면 참고만 하고 severity/ruleCode를 다시 판정하지 마세요. ");
        sb.append("ruleMatches의 judgmentCategory/reason은 Rule Engine이 이미 판단한 근거이니, ");
        sb.append("있으면 explanation 작성 시 참고하되 그대로 베끼지 말고 소비자가 이해할 수 있게 풀어 쓰세요.\n");
        sb.append("- evidence가 있으면 explanation 작성에 근거로 활용하세요.\n");
        sb.append("- reason에는 판단 근거를 간단히, explanation에는 소비자가 이해할 수 있는 설명을 쓰세요.\n");
        sb.append("- claims 각각에 대해 정확히 하나의 결과를 claimComparisons에 반환하세요.\n\n");

        sb.append("[제품]\n").append(toJson(request.product())).append("\n\n");
        sb.append("[확정된 원료]\n").append(toJson(request.ingredients())).append("\n\n");
        sb.append("[공식 인정 기능성]\n").append(toJson(request.officialFunctions())).append("\n\n");
        sb.append("[Rule 판정 결과] (참고용, 다시 판정하지 말 것)\n").append(toJson(request.ruleMatches())).append("\n\n");
        sb.append("[근거 문단] (참고용, 비어 있으면 무시)\n").append(toJson(request.evidence())).append("\n\n");
        sb.append("[Claims]\n").append(toJson(request.claims())).append("\n\n");

        sb.append("반드시 아래 JSON 형식으로만 응답하세요. 다른 설명은 붙이지 마세요.\n");
        sb.append("{\"claimComparisons\": [{\"claimId\": \"claim-1\", \"comparisonStatus\": \"STRONGER_THAN_OFFICIAL\", ");
        sb.append("\"officialFunction\": \"공식 인정 기능성 원문 또는 null\", \"reason\": \"판단 근거\", \"explanation\": \"소비자용 설명\"}]}\n");

        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            log.warn("Claim Comparison 프롬프트 직렬화 실패: {}", e.getMessage());
            return "[]";
        }
    }

    private ClaimComparisonResult parseResponse(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, ClaimComparisonResult.class);
        } catch (JacksonException e) {
            log.warn("Claim Comparison 응답 JSON 파싱 실패: {}", e.getMessage());
            return new ClaimComparisonResult(List.of());
        }
    }
}
