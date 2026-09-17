package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * docs/validation_dataset_v0.1.csv(117행, AiRuleEvaluator 대상 39개 규칙 x 3종)을 전부
 * 돌려 grounding 적용 전/후 정확도를 규칙별로 집계한다. 결과는 build/validation-results.csv에
 * 저장한다.
 *
 * <p><b>배치로 호출 수를 줄임</b>: 행마다 개별 호출하면 판정 234회(무료 티어 분당 15회
 * 한도에 크게 못 미쳐 429가 계속 남) + grounding용 임베딩 117회가 나가는데, 이 테스트는
 * 그 대신 (1) grounding 검색은 {@link RagRetrievalService#searchBatch}로 나눠 묶고,
 * (2) 판정은 여러 (규칙, 주장) 쌍을 한 프롬프트에 담아 JSON 배열로 한 번에 받는 자체 배치
 * 방식({@link #judgeBatch})을 써서 8건씩 묶는다. 이 배치 프롬프트는
 * 이 테스트 전용이며 {@link AiRuleEvaluator}(운영 코드, 아직 evaluateBatch 미구현)는
 * 건드리지 않는다. 배치 응답 파싱이 깨지면(개수 불일치 등) 그 배치만 개별
 * {@code AiRuleEvaluator.evaluate()} 호출로 폴백한다(searchBatch의 폴백 관례와 동일).
 * 그래도 분당 한도를 넘지 않도록 호출 사이 지연을 둔다. GEMINI_API_KEY 없으면 스킵.
 *
 * <p><b>{@link #RUN_WITHOUT_GROUNDING_BASELINE}</b>: grounding 없는 baseline과의 A/B
 * 비교는 이미 한 번 완료했다(결과: MATCHED 37/39→39/39 개선, NOT_MATCHED 39/39 동일,
 * REVIEW_REQUIRED 1/39→0/39 악화 — grounding을 유지하기로 한 결정 자체는 안 바뀜).
 * 그래서 기본값은 false — 프롬프트를 반복 조정하는 동안은 실제 운영 구성인 "grounding
 * 있음"만 돌려 호출을 절반으로 줄인다. 프롬프트를 최종 확정한 뒤 딱 한 번 true로 돌려서
 * 최종 before/after를 다시 비교한다.
 */
class AiRuleEvaluatorValidationDatasetTest {

    private static final boolean RUN_WITHOUT_GROUNDING_BASELINE = false;
    private static final int BATCH_SIZE = 8;
    private static final int EMBED_BATCH_LIMIT = 40;
    private static final long SLEEP_BETWEEN_CALLS_MS = 4500L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String STATUS_VALUES = "MATCHED, NOT_MATCHED, REVIEW_REQUIRED";
    private static final String REASON_CODE_VALUES =
            "SUPPORTED_CONDITION_CONFIRMED, CONDITION_NOT_MET, EXCEPTION_CONFIRMED, "
                    + "SEMANTIC_COMPARISON_REQUIRED, OFFICIAL_FUNCTION_DATA_INCOMPLETE, "
                    + "OFFICIAL_FUNCTION_EXACT_MATCH, OUTSIDE_SUPPORTED_LANGUAGE";

    @Test
    void 검증_데이터셋_117행_정확도_측정() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");

        Map<String, List<String>> sourceIdsByRule = loadRuleSourceIds();
        List<Map<String, String>> rows = readValidationDataset();

        // 팀 결정(2026-09-17): 판정용 grounding은 OFF가 최종 구성이라, RUN_WITHOUT_GROUNDING_
        // BASELINE이 false면(기본값) grounding 검색 자체를 안 한다 — 안 쓸 임베딩 호출로
        // 쿼터를 낭비하고 429 위험을 키울 이유가 없다.
        Map<String, List<Evidence>> groundingByRow = new LinkedHashMap<>();
        if (RUN_WITHOUT_GROUNDING_BASELINE) {
            GeminiEmbeddingClient embeddingClient = new GeminiEmbeddingClient(apiKey, "gemini-embedding-001");
            RagRetrievalService ragService = new RagRetrievalService(embeddingClient);
            // batchEmbedContents는 배치 안 항목 수만큼 embed_content 분당 쿼터(100)를 그대로
            // 차감하는 것으로 보여 EMBED_BATCH_LIMIT을 40으로 넉넉히 낮추고, 실패 시 재시도한다.
            System.out.println("=== grounding 검색 배치 조회 (" + rows.size() + "건, " + EMBED_BATCH_LIMIT + "건씩 나눠 호출) ===");
            List<RagRetrievalService.ClaimQuery> allQueries = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> row = rows.get(i);
                List<String> sourceIds = sourceIdsByRule.getOrDefault(row.get("ruleCode"), List.of());
                allQueries.add(new RagRetrievalService.ClaimQuery("row-" + i, row.get("claimText"), sourceIds));
            }
            for (int start = 0; start < allQueries.size(); start += EMBED_BATCH_LIMIT) {
                List<RagRetrievalService.ClaimQuery> chunk =
                        allQueries.subList(start, Math.min(start + EMBED_BATCH_LIMIT, allQueries.size()));
                Map<String, List<Evidence>> chunkResult = callWithRetry(
                        () -> ragService.searchBatch(chunk, 3), "grounding batch [" + start + "]");
                groundingByRow.putAll(chunkResult);
                sleep(SLEEP_BETWEEN_CALLS_MS);
            }
        }

        List<BatchItem> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            Rule rule = CanonicalRuleFixture.rule(row.get("ruleCode"));
            List<String> grounding = groundingByRow.getOrDefault("row-" + i, List.of()).stream()
                    .map(e -> "[" + e.sourceId() + "] " + e.text())
                    .toList();
            items.add(new BatchItem(row.get("ruleCode"), row.get("expectedStatus"), row.get("claimText"),
                    rule.getJudgmentCategory(), rule.getApplicationConditions(), rule.getExceptions(),
                    rule.getCandidateExamples(), grounding));
        }

        // 팀 결정(2026-09-17): 판정용 grounding은 OFF가 최종 프로덕션 구성 — 그래서 주 실행
        // 대상("with" 변수명은 유지하되 실제로는 grounding=false)을 false로 바꿨다.
        List<Judgment> without = null;
        if (RUN_WITHOUT_GROUNDING_BASELINE) {
            System.out.println("=== 판정 배치 호출 — grounding 있음 (" + items.size() + "건, " + BATCH_SIZE + "건씩 묶음) ===");
            without = judgeAllBatched(geminiClient, items, true);
        }
        System.out.println("=== 판정 배치 호출 — grounding 없음(최종 프로덕션 구성) (" + items.size() + "건, " + BATCH_SIZE + "건씩 묶음) ===");
        List<Judgment> with = judgeAllBatched(geminiClient, items, false);

        writeResultsAndSummary(items, without, with);
    }

    /**
     * 배치(8건 묶음) 측정에서 REVIEW_REQUIRED 인식률이 5/39로 낮았는데, 이게 모델 한계인지
     * "한 프롬프트에 8건을 몰아넣은 측정 방식" 탓인지 가른다. 운영 코드 경로를 그대로 쓴다 —
     * {@link AiRuleEvaluator#evaluate}(규칙 1개당 호출 1번, 운영 프롬프트·운영 파싱). grounding은
     * 미리 배치로 받아 주입해서 임베딩 호출은 1회만 쓴다. 판정 호출 39회 + 임베딩 1회.
     */
    @Test
    void REVIEW_REQUIRED_39건_운영경로_단건호출_재측정() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        GeminiEmbeddingClient embeddingClient = new GeminiEmbeddingClient(apiKey, "gemini-embedding-001");
        RagRetrievalService ragService = new RagRetrievalService(embeddingClient);

        Map<String, List<String>> sourceIdsByRule = loadRuleSourceIds();
        List<Map<String, String>> targetRows = readValidationDataset().stream()
                .filter(row -> "REVIEW_REQUIRED".equals(row.get("expectedStatus")))
                .toList();
        System.out.println("=== REVIEW_REQUIRED " + targetRows.size() + "건, 운영 경로 단건 호출로 재측정 ===");

        List<RagRetrievalService.ClaimQuery> queries = new ArrayList<>();
        for (int i = 0; i < targetRows.size(); i++) {
            Map<String, String> row = targetRows.get(i);
            queries.add(new RagRetrievalService.ClaimQuery("row-" + i, row.get("claimText"),
                    sourceIdsByRule.getOrDefault(row.get("ruleCode"), List.of())));
        }
        Map<String, List<Evidence>> groundingByRow =
                callWithRetry(() -> ragService.searchBatch(queries, 3), "grounding batch");
        sleep(SLEEP_BETWEEN_CALLS_MS);

        Map<String, List<String>> groundingByClaimText = new LinkedHashMap<>();
        for (int i = 0; i < targetRows.size(); i++) {
            groundingByClaimText.put(targetRows.get(i).get("claimText"),
                    groundingByRow.getOrDefault("row-" + i, List.of()).stream()
                            .map(e -> "[" + e.sourceId() + "] " + e.text())
                            .toList());
        }

        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient,
                (rule, claimText) -> groundingByClaimText.getOrDefault(claimText, List.of()));

        int correct = 0;
        List<String> lines = new ArrayList<>();
        lines.add("ruleCode,actualStatus,actualReasonCode,claimText");
        for (Map<String, String> row : targetRows) {
            Rule rule = CanonicalRuleFixture.rule(row.get("ruleCode"));
            RuleAnalysisRequest.Claim claim = new RuleAnalysisRequest.Claim(
                    "v-" + row.get("ruleCode"), row.get("claimText"),
                    RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스");
            RuleAnalysisRequest request = new RuleAnalysisRequest(claim, List.of(), Set.of(),
                    new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));

            RuleEvaluation result = callWithRetry(() -> evaluator.evaluate(rule, request), row.get("ruleCode"));
            boolean match = result.status() == RuleEvaluation.Status.REVIEW_REQUIRED;
            if (match) correct++;
            System.out.printf("%-27s %-16s (%s) %s%n", row.get("ruleCode"), result.status(),
                    match ? "O" : "X", result.reasonCode());
            lines.add(String.join(",", row.get("ruleCode"), result.status().name(), result.reasonCode().name(),
                    "\"" + row.get("claimText").replace("\"", "\"\"") + "\""));
            sleep(SLEEP_BETWEEN_CALLS_MS);
        }

        Path outPath = Path.of("build", "review-required-batch1.csv");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, lines, StandardCharsets.UTF_8);

        System.out.printf("%n=== 결과: 단건 호출 %d/%d (%.1f%%) — 배치 8건 묶음일 때는 5/39 (12.8%%) ===%n",
                correct, targetRows.size(), 100.0 * correct / targetRows.size());
        System.out.println("결과 저장: " + outPath.toAbsolutePath());
    }

    /**
     * 라벨 재검토 대상 7건(REVIEW_REQUIRED 라벨인데 모델이 MATCHED로 판정 — 라벨이 과보수적일
     * 가능성)의 실제 판단 이유(reason)를 팀 공유용으로 캡처한다. 팀 결정(grounding OFF)을
     * 반영해 groundingProvider=null로 평가. 판정 호출 7회.
     */
    @Test
    void 라벨재검토_7건_판단근거_캡처() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        List<String> targetRuleCodes = List.of("L03_EYE_DISEASE", "E03_GENERATION", "T04_ANTIAGING",
                "L01_VISION", "P03_DISEASE_GUT", "B02_VIRUS", "E01_VESSEL");
        Map<String, String> claimTextByRule = readValidationDataset().stream()
                .filter(row -> targetRuleCodes.contains(row.get("ruleCode"))
                        && "REVIEW_REQUIRED".equals(row.get("expectedStatus")))
                .collect(java.util.stream.Collectors.toMap(row -> row.get("ruleCode"), row -> row.get("claimText")));

        List<String> lines = new ArrayList<>();
        lines.add("ruleCode,claimText,status,reasonCode,reason");
        for (String ruleCode : targetRuleCodes) {
            String claimText = claimTextByRule.get(ruleCode);
            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            RuleAnalysisRequest.Claim claim = new RuleAnalysisRequest.Claim(
                    "v-" + ruleCode, claimText, RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스");
            RuleAnalysisRequest request = new RuleAnalysisRequest(claim, List.of(), Set.of(),
                    new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));

            RuleEvaluation result = callWithRetry(() -> evaluator.evaluate(rule, request), ruleCode);
            System.out.printf("%-20s claim=\"%s\"%n  status=%s reasonCode=%s%n  reason=%s%n%n",
                    ruleCode, claimText, result.status(), result.reasonCode(), result.reason());
            lines.add(String.join(",", ruleCode,
                    "\"" + claimText.replace("\"", "\"\"") + "\"",
                    result.status().name(), result.reasonCode().name(),
                    "\"" + result.reason().replace("\"", "\"\"") + "\""));
            sleep(SLEEP_BETWEEN_CALLS_MS);
        }

        Path outPath = Path.of("build", "label-review-evidence.csv");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, lines, StandardCharsets.UTF_8);
        System.out.println("결과 저장: " + outPath.toAbsolutePath());
    }

    private List<Judgment> judgeAllBatched(GeminiClient geminiClient, List<BatchItem> items, boolean useGrounding) {
        List<Judgment> results = new ArrayList<>(items.size());
        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            List<BatchItem> chunk = items.subList(start, Math.min(start + BATCH_SIZE, items.size()));
            List<Judgment> chunkResult = judgeBatch(geminiClient, chunk, useGrounding);
            results.addAll(chunkResult);
            System.out.printf("  [%d/%d] 완료%n", results.size(), items.size());
            sleep(SLEEP_BETWEEN_CALLS_MS);
        }
        return results;
    }

    /** chunk(N건)를 한 프롬프트로 묶어 판정하고, 파싱 실패 시 개별 evaluate() 호출로 폴백한다. */
    private List<Judgment> judgeBatch(GeminiClient geminiClient, List<BatchItem> chunk, boolean useGrounding) {
        String prompt = buildBatchPrompt(chunk, useGrounding);
        try {
            String rawResponse = callWithRetry(() -> geminiClient.generate(prompt, true), "판정 배치");
            List<RawBatchJudgment> parsed = OBJECT_MAPPER.readValue(rawResponse, new TypeReference<List<RawBatchJudgment>>() {
            });
            if (parsed.size() != chunk.size()) {
                throw new IllegalStateException("배치 응답 개수 불일치: 기대 " + chunk.size() + ", 실제 " + parsed.size());
            }
            Map<Integer, RawBatchJudgment> byIndex = new LinkedHashMap<>();
            for (RawBatchJudgment j : parsed) {
                byIndex.put(j.index(), j);
            }
            List<Judgment> results = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                RawBatchJudgment j = byIndex.get(i + 1);
                if (j == null) {
                    throw new IllegalStateException("배치 응답에 index=" + (i + 1) + " 없음");
                }
                results.add(new Judgment(j.status(), j.reasonCode(), j.reason(), j.needsOutsideContext()));
            }
            return results;
        } catch (RuntimeException e) {
            System.out.println("  [배치 파싱 실패, 개별 호출로 폴백] " + e.getMessage());
            return fallbackIndividually(geminiClient, chunk, useGrounding);
        }
    }

    private List<Judgment> fallbackIndividually(GeminiClient geminiClient, List<BatchItem> chunk, boolean useGrounding) {
        List<Judgment> results = new ArrayList<>(chunk.size());
        for (BatchItem item : chunk) {
            String prompt = buildBatchPrompt(List.of(item), useGrounding);
            try {
                String rawResponse = callWithRetry(() -> geminiClient.generate(prompt, true), "개별 폴백");
                List<RawBatchJudgment> parsed = OBJECT_MAPPER.readValue(rawResponse, new TypeReference<List<RawBatchJudgment>>() {
                });
                RawBatchJudgment j = parsed.get(0);
                results.add(new Judgment(j.status(), j.reasonCode(), j.reason(), j.needsOutsideContext()));
            } catch (RuntimeException e) {
                results.add(new Judgment("REVIEW_REQUIRED", "SEMANTIC_COMPARISON_REQUIRED",
                        "TEST_HARNESS_ERROR: " + e.getMessage(), null));
            }
            sleep(SLEEP_BETWEEN_CALLS_MS);
        }
        return results;
    }

    private String buildBatchPrompt(List<BatchItem> chunk, boolean useGrounding) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 건강기능식품 광고 문구가 특정 규칙에 해당하는지 판정하는 검수 도구입니다.\n");
        sb.append("아래는 서로 다른 (규칙, 주장) 쌍 ").append(chunk.size()).append("개입니다. 각각을 독립적으로 판정하세요.\n\n");

        for (int i = 0; i < chunk.size(); i++) {
            BatchItem item = chunk.get(i);
            sb.append("[항목 ").append(i + 1).append("]\n");
            sb.append("분류: ").append(item.judgmentCategory()).append('\n');
            sb.append("적용 조건: ").append(item.applicationConditions()).append('\n');
            if (isNotBlank(item.exceptions())) {
                sb.append("예외 사항: ").append(item.exceptions()).append('\n');
            }
            if (isNotBlank(item.candidateExamples())) {
                sb.append("참고 예시(전체 목록 아님, 힌트일 뿐): ").append(item.candidateExamples()).append('\n');
            }
            sb.append("문장: ").append(item.claimText()).append('\n');
            sb.append("문맥: PRODUCT_HEALTH_EFFECT_COPY (근거: 검증 데이터셋 케이스)\n");
            if (useGrounding && !item.groundingTexts().isEmpty()) {
                sb.append("[근거 문서 원문] (참고용, 관련도 높은 문단 발췌)\n");
                for (String text : item.groundingTexts()) {
                    sb.append("- ").append(text).append('\n');
                }
            }
            sb.append('\n');
        }

        sb.append("MATCHED는 \"이 Claim이 분류가 우려하는 광고 위반 패턴에 실제로 해당한다\"는 뜻입니다. ");
        sb.append("[적용 조건]은 그 위반 여부를 판단하는 기준일 뿐, 조건 문장이 문자 그대로 참이라고 해서 ");
        sb.append("무조건 MATCHED가 되는 건 아닙니다 — 조건을 충족하는 것 자체가 오히려 정상적인 표시(위반 아님)를 ");
        sb.append("뜻하는 항목도 있으니, 분류명과 취지를 보고 실제로 위반인지 최종 판단하세요. ");
        sb.append("위반 패턴에 해당하고 예외 사항에 해당하지 않으면 MATCHED, ");
        sb.append("위반이 아니면 NOT_MATCHED로 답하세요.\n\n");

        sb.append("REVIEW_REQUIRED는 실패나 회피가 아니라, 문장만으로는 확정할 수 없을 때 내려야 하는 ");
        sb.append("올바른 판단입니다 — 억지로 MATCHED/NOT_MATCHED 중 하나를 고르지 마세요. 다음 중 하나라도 ");
        sb.append("해당하면 REVIEW_REQUIRED를 선택하세요: ");
        sb.append("(1) 이 문장이 제품 효과를 암시하는지 단순 정보 제공인지 해석이 갈릴 수 있음, ");
        sb.append("(2) 위반/정상 여부가 문장 밖의 정황(전체 광고 맥락, 이미지, 실제 데이터)에 달려 있어 ");
        sb.append("이 문장만으로는 그 정황을 알 수 없음, ");
        sb.append("(3) 위에 근거 문서 원문이 제공돼 있어도 그 문서는 일반적 기준일 뿐 이 Claim의 구체적 정황(예: 실제 시점·비교대상·수치의 진위)까지 ");
        sb.append("확인해주지는 않음 — 근거 문서가 있다는 것과 이 Claim이 확정적이라는 것은 별개입니다.\n");
        sb.append("각 항목마다 답하기 전에 먼저 needsOutsideContext를 판단하세요: 그 항목의 위반 여부를 확정하려면 ");
        sb.append("문장 밖 정보 — 광고 전체 레이아웃·구획, 함께 실린 이미지, 이 제품의 실제 원료·인정 기능성 데이터, ");
        sb.append("인용의 출처·시점, 수치의 실제 근거 자료 — 를 봐야 합니까?\n");
        sb.append("- true: 문장 밖 정보 없이는 확정할 수 없다 → status는 반드시 REVIEW_REQUIRED\n");
        sb.append("- false: 이 문장 자체가 위반인지 아닌지를 명확히 드러낸다 → MATCHED 또는 NOT_MATCHED\n");
        sb.append("막연한 표현(무엇을 어떻게 한다는 게 특정되지 않은 문구), 질문·권유형 문구, 느낌·기분 표현, ");
        sb.append("대상·상황만 언급하고 효과는 말하지 않는 문구는 대부분 true입니다.\n");
        sb.append("status는 다음 중 정확히 하나: ").append(STATUS_VALUES).append(".\n");
        sb.append("reasonCode는 다음 중 정확히 하나: ").append(REASON_CODE_VALUES).append(".\n");
        sb.append("reason에는 판단 근거를 한국어 한두 문장으로 쓰세요. 추측하지 말고, 모르면 REVIEW_REQUIRED를 쓰세요.\n\n");

        sb.append("반드시 아래 JSON 배열 형식으로만 응답하세요. index는 위 [항목 N]의 N과 정확히 일치시키고, ");
        sb.append("정확히 ").append(chunk.size()).append("개를 순서 상관없이(index로 식별) 반환하세요. 다른 설명은 붙이지 마세요.\n");
        sb.append("[{\"index\": 1, \"needsOutsideContext\": true, \"status\": \"...\", \"reasonCode\": \"...\", \"reason\": \"...\"}, ...]\n");

        return sb.toString();
    }

    private void writeResultsAndSummary(List<BatchItem> items, List<Judgment> without, List<Judgment> with) throws IOException {
        Path outPath = Path.of("build", "validation-results.csv");
        Files.createDirectories(outPath.getParent());

        boolean hasBaseline = without != null;
        int matchWithout = 0, matchWith = 0;
        Map<String, int[]> perRule = new LinkedHashMap<>(); // [count, matchWithout, matchWith]

        try (BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            writer.write(hasBaseline
                    ? "ruleCode,expectedStatus,withoutStatus,withoutMatch,withoutReasonCode,"
                            + "withGroundingStatus,withGroundingMatch,withGroundingReasonCode,claimText"
                    : "ruleCode,expectedStatus,withGroundingStatus,withGroundingMatch,withGroundingReasonCode,needsOutsideContext,claimText");
            writer.newLine();

            for (int i = 0; i < items.size(); i++) {
                BatchItem item = items.get(i);
                Judgment g = with.get(i);
                boolean matchG = g.status().equals(item.expectedStatus());
                if (matchG) matchWith++;

                int[] agg = perRule.computeIfAbsent(item.ruleCode(), k -> new int[3]);
                agg[0]++;
                if (matchG) agg[2]++;

                if (hasBaseline) {
                    Judgment w = without.get(i);
                    boolean matchW = w.status().equals(item.expectedStatus());
                    if (matchW) matchWithout++;
                    agg[1] += matchW ? 1 : 0;
                    writer.write(String.join(",",
                            item.ruleCode(), item.expectedStatus(),
                            w.status(), String.valueOf(matchW), w.reasonCode(),
                            g.status(), String.valueOf(matchG), g.reasonCode(),
                            "\"" + item.claimText().replace("\"", "\"\"") + "\""));
                } else {
                    writer.write(String.join(",",
                            item.ruleCode(), item.expectedStatus(),
                            g.status(), String.valueOf(matchG), g.reasonCode(),
                            String.valueOf(g.needsOutsideContext()),
                            "\"" + item.claimText().replace("\"", "\"\"") + "\""));
                }
                writer.newLine();
            }
        }

        System.out.println();
        int total = items.size();
        if (hasBaseline) {
            System.out.println("=== 규칙별 정확도 (grounding 없음 -> 있음) ===");
            for (var e : perRule.entrySet()) {
                int[] agg = e.getValue();
                System.out.printf("%-25s %d/%d -> %d/%d%n", e.getKey(), agg[1], agg[0], agg[2], agg[0]);
            }
            System.out.printf("%n전체(%d행): grounding 없음=%d/%d(%.1f%%) grounding 있음=%d/%d(%.1f%%)%n",
                    total, matchWithout, total, 100.0 * matchWithout / total,
                    matchWith, total, 100.0 * matchWith / total);
        } else {
            System.out.println("=== 규칙별 정확도 (grounding 있음, baseline 생략) ===");
            for (var e : perRule.entrySet()) {
                int[] agg = e.getValue();
                System.out.printf("%-25s %d/%d%n", e.getKey(), agg[2], agg[0]);
            }
            System.out.printf("%n전체(%d행): grounding 있음=%d/%d(%.1f%%)%n",
                    total, matchWith, total, 100.0 * matchWith / total);
        }
        System.out.println("결과 저장: " + outPath.toAbsolutePath());
    }

    /** 429 등 일시적 실패에 지수 백오프로 재시도한다(최대 4회) — 실패해도 조용히 넘기지 않고 마지막엔 던진다. */
    private static <T> T callWithRetry(java.util.function.Supplier<T> call, String label) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                long backoffMs = 15_000L * (attempt + 1);
                System.out.println("  [재시도 " + (attempt + 1) + "/4, " + (backoffMs / 1000) + "초 대기] "
                        + label + ": " + e.getMessage());
                sleep(backoffMs);
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** rules_v0.1.csv의 ruleCode -> sourceIds 매핑. DB 없이 RuleSourceResolver 대신 직접 읽는다. */
    private static Map<String, List<String>> loadRuleSourceIds() throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (InputStream in = AiRuleEvaluatorValidationDatasetTest.class
                .getResourceAsStream("/db/migration/data/rules_v0.1.csv")) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("﻿", "");
            List<List<String>> csvRows = parseCsv(content);
            List<String> header = csvRows.get(0);
            int ruleCodeIdx = header.indexOf("ruleCode");
            int sourceIdsIdx = header.indexOf("sourceIds");
            for (int i = 1; i < csvRows.size(); i++) {
                List<String> row = csvRows.get(i);
                result.put(row.get(ruleCodeIdx), parseJsonStringArray(row.get(sourceIdsIdx)));
            }
        }
        return result;
    }

    private static List<Map<String, String>> readValidationDataset() throws IOException {
        Path path = Path.of("..", "docs", "validation_dataset_v0.1.csv");
        if (!Files.exists(path)) {
            path = Path.of("docs", "validation_dataset_v0.1.csv");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8).replace("﻿", "");
        List<List<String>> csvRows = parseCsv(content);
        List<String> header = csvRows.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < csvRows.size(); i++) {
            List<String> row = csvRows.get(i);
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c), c < row.size() ? row.get(c) : "");
            }
            rows.add(map);
        }
        return rows;
    }

    private static List<String> parseJsonStringArray(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        String trimmed = json.strip();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        for (String part : trimmed.split(",")) {
            String v = part.strip();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            if (!v.isBlank()) {
                result.add(v);
            }
        }
        return result;
    }

    /** Test-only RFC4180 reader (CanonicalRuleFixture와 동일 방식): 따옴표 안 콤마·줄바꿈 보존. */
    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append(c);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && (c == ',' || c == '\n' || c == '\r')) {
                row.add(field.toString());
                field.setLength(0);
                if (c != ',') {
                    rows.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
            } else {
                field.append(c);
            }
        }
        if (quoted) throw new IllegalStateException("Unterminated CSV quote");
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private record BatchItem(String ruleCode, String expectedStatus, String claimText, String judgmentCategory,
                              String applicationConditions, String exceptions, String candidateExamples,
                              List<String> groundingTexts) {
    }

    private record Judgment(String status, String reasonCode, String reason, Boolean needsOutsideContext) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawBatchJudgment(int index, Boolean needsOutsideContext, String status, String reasonCode, String reason) {
    }
}
