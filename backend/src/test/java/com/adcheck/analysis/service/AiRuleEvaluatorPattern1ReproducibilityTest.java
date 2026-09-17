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
 * 패턴1 프롬프트 수정 후 1회 실행(AiRuleEvaluatorPattern1RetestTest)에서 E04/B03을 제외한
 * 14개 규칙이 2/3~3/3으로 개선된 걸 확인했지만, 그게 우연한 1회였는지 안정적인지는 아직
 * 모른다. 같은 14개 규칙 전체 claim(42건)을 2회 더 재실행해(총 3회 데이터 확보) 28개 파일럿의
 * 나머지 9개(1차)/7개(2차)와 같은 기준(3회 반복 안정)으로 등록 여부를 판단하기 위한 테스트.
 * E04_ALIAS(진짜 모델 약점 확인됨)와 B03_OTHER_ORAL(officialFunctions 채워야 통과 — 이
 * 하네스는 항상 빈 리스트를 넘겨서 애초에 대상에서 제외)은 포함하지 않는다.
 */
class AiRuleEvaluatorPattern1ReproducibilityTest {

    private static final List<String> TARGET_RULE_CODES = List.of(
            "C04_DISEASE_INFO_LINK", "C13_TESTIMONIAL", "C14_EXPERT_ENDORSEMENT",
            "C22_SUPERLATIVE", "M02_LIVER_MARKER", "S02_BODY_AREA",
            "C03_MEDICINE_CONFUSION", "C21_UNFAIR_COMPARISON", "E01_VESSEL", "G05_GLUCOSE_DIET",
            "M04_REGEN_CANCER", "B01_IMMUNE_INFLAMMATION", "L01_VISION", "L03_EYE_DISEASE"
    );

    @Test
    void 패턴1_14개_규칙_2회_추가_재실행() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient geminiClient = new GeminiClient(apiKey, "gemini-3.5-flash-lite");
        AiRuleEvaluator evaluator = new AiRuleEvaluator(geminiClient, null);

        List<Map<String, String>> rows = readValidationDataset().stream()
                .filter(r -> TARGET_RULE_CODES.contains(r.get("ruleCode")))
                .toList();
        System.out.println("대상 claim 수(회당): " + rows.size());

        List<String> lines = new ArrayList<>();
        lines.add("runNumber,ruleCode,expectedStatus,claimText,actualStatus,match,reasonCode,reason");

        for (int runNumber = 2; runNumber <= 3; runNumber++) {
            System.out.println("=== 패턴1 수정 후 재실행 #" + runNumber + " ===");
            int correct = 0;
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

                System.out.printf("  %-25s expected=%-16s actual=%-16s(%s) %s%n",
                        ruleCode, expected, result.status(), match ? "O" : "X", result.reasonCode());
                lines.add(String.join(",", String.valueOf(runNumber), ruleCode, expected,
                        "\"" + claimText.replace("\"", "\"\"") + "\"",
                        result.status().name(), String.valueOf(match), result.reasonCode().name(),
                        "\"" + result.reason().replace("\"", "\"\"") + "\""));
                sleep(4500);
            }
            System.out.printf("재실행 #%d: %d/%d (%.1f%%)%n%n", runNumber, correct, rows.size(), 100.0 * correct / rows.size());
        }

        Path outPath = Path.of("build", "pattern1-reproducibility-results.csv");
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
