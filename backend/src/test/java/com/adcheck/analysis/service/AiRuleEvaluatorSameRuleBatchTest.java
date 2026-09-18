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
 * 호출 지연시간(팀원 실측 2분30초) 문제를 풀기 위한 "같은 규칙 + 여러 Claim" 배치 실험.
 * 이전에 실패한 "Claim 1개 + 규칙 여러 개" 배치(판단 기준이 항목마다 달라 게이트가 흔들림)와
 * 반대 축이다 — 여기서는 판단 기준(적용 조건·예외 사항)이 배치 전체에서 하나로 고정되므로 그
 * 실패 원인이 구조적으로 없다. 이미 개별 호출로 3회 반복 9/9 안정 확인된 1차 파일럿 9개 규칙의
 * 3개 claim씩(총 27건)을 규칙당 1번의 배치 호출(총 9번 호출)로 묶어서, 개별 호출 대비 정확도가
 * 유지되는지 확인한다 — 호출 수를 27회에서 9회로 줄일 수 있는지가 핵심 질문.
 */
class AiRuleEvaluatorSameRuleBatchTest {

    private static final List<String> TARGET_RULE_CODES = List.of(
            "B02_VIRUS", "C27_FUNCTION_SYNERGY", "G03_WEIGHT_FAT", "G04_SATIETY_COFFEE",
            "O01_SEXUAL", "P05_INFANT", "R01_COLD", "R02_MENOPAUSE", "T04_ANTIAGING"
    );

    @Test
    void 같은_규칙_여러_claim_배치_1차파일럿9개_정확도_실측() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        Map<String, List<Map<String, String>>> byRule = readValidationDataset().stream()
                .filter(r -> TARGET_RULE_CODES.contains(r.get("ruleCode")))
                .collect(java.util.stream.Collectors.groupingBy(r -> r.get("ruleCode"),
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));

        List<String> lines = new ArrayList<>();
        lines.add("ruleCode,expectedStatus,claimText,actualStatus,match,reasonCode,reason");
        int correct = 0;
        int total = 0;
        long start = System.currentTimeMillis();

        for (String ruleCode : TARGET_RULE_CODES) {
            List<Map<String, String>> rows = byRule.getOrDefault(ruleCode, List.of());
            if (rows.isEmpty()) continue;

            Rule rule = CanonicalRuleFixture.rule(ruleCode);
            List<RuleAnalysisRequest> requests = rows.stream()
                    .map(row -> {
                        Claim claim = new Claim("v-" + ruleCode, row.get("claimText"),
                                PRODUCT_HEALTH_EFFECT_COPY, "검증 데이터셋 케이스");
                        return new RuleAnalysisRequest(claim, List.of(), Set.of(),
                                new RuleAnalysisRequest.OfficialFunctions(List.<RuleOfficialFunctionContext>of(), false, false));
                    })
                    .toList();

            List<RuleEvaluation> results = evaluator.evaluateAcrossClaims(rule, requests);

            for (int i = 0; i < rows.size(); i++) {
                String expected = rows.get(i).get("expectedStatus");
                String claimText = rows.get(i).get("claimText");
                RuleEvaluation result = results.get(i);
                boolean match = result.status().name().equals(expected);
                total++;
                if (match) correct++;

                System.out.printf("%-25s expected=%-16s actual=%-16s(%s) %s%n",
                        ruleCode, expected, result.status(), match ? "O" : "X", result.reasonCode());
                lines.add(String.join(",", ruleCode, expected,
                        "\"" + claimText.replace("\"", "\"\"") + "\"",
                        result.status().name(), String.valueOf(match), result.reasonCode().name(),
                        "\"" + result.reason().replace("\"", "\"\"") + "\""));
            }
            sleep(4500);
        }

        long elapsedMs = System.currentTimeMillis() - start;
        System.out.printf("%n전체: %d/%d (%.1f%%), 배치 호출 %d회, 총 소요 %.1f초%n",
                correct, total, 100.0 * correct / total, TARGET_RULE_CODES.size(), elapsedMs / 1000.0);

        Path outPath = Path.of("build", "same-rule-batch-results.csv");
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
