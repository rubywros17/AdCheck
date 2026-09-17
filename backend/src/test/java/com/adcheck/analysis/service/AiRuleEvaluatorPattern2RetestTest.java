package com.adcheck.analysis.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.CanonicalRuleFixture;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleEvaluation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;

/**
 * 재현성 3회 검증에서 "매번 똑같이 틀림"으로 분류된 7개 규칙(C14/C21/G05/M04/L01/M02/S02)의
 * 전체 claim(21건)을 패턴2 프롬프트 수정("핵심 단어 등장 ≠ 자동 MATCHED" + "예외 사항의 '별도
 * 검토' 문구는 REVIEW_REQUIRED 신호") 이후 다시 돌려 개선 여부를 확인한다. 아직 1회 실행 —
 * 개선이 보이면 다른 규칙들처럼 재현성 확인을 위해 2회 더 재실행할 예정.
 */
class AiRuleEvaluatorPattern2RetestTest {

    private static final List<String> TARGET_RULE_CODES = List.of(
            "C14_EXPERT_ENDORSEMENT", "C21_UNFAIR_COMPARISON", "G05_GLUCOSE_DIET",
            "M04_REGEN_CANCER", "L01_VISION", "M02_LIVER_MARKER", "S02_BODY_AREA"
    );

    @Test
    void 패턴2_수정후_7개_규칙_전체_claim_재테스트() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        List<Map<String, String>> rows = readValidationDataset().stream()
                .filter(r -> TARGET_RULE_CODES.contains(r.get("ruleCode")))
                .toList();
        System.out.println("대상 claim 수: " + rows.size());

        List<String> lines = new ArrayList<>();
        lines.add("ruleCode,expectedStatus,claimText,actualStatus,match,reasonCode,reason");
        int correct = 0;
        Map<String, int[]> perRule = new java.util.LinkedHashMap<>();

        for (Map<String, String> row : rows) {
            String ruleCode = row.get("ruleCode");
            String claimText = row.get("claimText");
            String expected = row.get("expectedStatus");

            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            Claim claim = new Claim("v-" + ruleCode, claimText, PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스");
            RuleAnalysisRequest request = new RuleAnalysisRequest(claim, List.of(), Set.of(),
                    new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));

            RuleEvaluation result = callWithRetry(() -> evaluator.evaluate(rule, request), ruleCode);
            boolean match = result.status().name().equals(expected);
            if (match) correct++;
            int[] agg = perRule.computeIfAbsent(ruleCode, k -> new int[2]);
            agg[0]++;
            if (match) agg[1]++;

            System.out.printf("%-25s expected=%-16s actual=%-16s(%s) %s%n",
                    ruleCode, expected, result.status(), match ? "O" : "X", result.reasonCode());
            lines.add(String.join(",", ruleCode, expected,
                    "\"" + claimText.replace("\"", "\"\"") + "\"",
                    result.status().name(), String.valueOf(match), result.reasonCode().name(),
                    "\"" + result.reason().replace("\"", "\"\"") + "\""));
            sleep(4500);
        }

        System.out.println();
        System.out.println("=== 규칙별 결과 ===");
        for (var e : perRule.entrySet()) {
            System.out.printf("%-25s %d/%d%n", e.getKey(), e.getValue()[1], e.getValue()[0]);
        }
        System.out.printf("%n전체: %d/%d (%.1f%%)%n", correct, rows.size(), 100.0 * correct / rows.size());

        Path outPath = Path.of("build", "pattern2-retest-results.csv");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, lines, StandardCharsets.UTF_8);
        System.out.println("결과 저장: " + outPath.toAbsolutePath());
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
            Map<String, String> map = new java.util.LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c), c < row.size() ? row.get(c) : "");
            }
            rows.add(map);
        }
        return rows;
    }

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

    private static RuleEvaluation callWithRetry(java.util.function.Supplier<RuleEvaluation> call, String label) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                sleep(3000L * (attempt + 1));
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
}
