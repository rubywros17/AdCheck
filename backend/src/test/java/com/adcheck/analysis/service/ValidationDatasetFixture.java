package com.adcheck.analysis.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code docs/validation_dataset_v0.1.csv}(규칙별 정답 라벨이 붙은 검증 케이스)를 읽어오는
 * 공용 헬퍼. 같은 리더가 실측 테스트마다 복사돼 있었는데, 새로 추가할 때마다 또 베끼지 않도록
 * 여기로 모았다(기존 테스트들은 API 키가 있어야 도는 실험용이라 이번엔 건드리지 않았다).
 *
 * <p>테스트가 모듈 루트({@code backend/})에서 돌 수도, 저장소 루트에서 돌 수도 있어 두 경로를
 * 모두 시도한다.
 */
final class ValidationDatasetFixture {

    private ValidationDatasetFixture() {
    }

    /** CSV 전체를 헤더명 → 값 맵의 리스트로 읽는다. */
    static List<Map<String, String>> rows() throws IOException {
        Path path = Path.of("..", "docs", "validation_dataset_v0.1.csv");
        if (!Files.exists(path)) {
            path = Path.of("docs", "validation_dataset_v0.1.csv");
        }
        List<List<String>> csvRows = parseCsv(
                Files.readString(path, StandardCharsets.UTF_8).replace("﻿", ""));
        List<String> header = csvRows.getFirst();
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

    /**
     * 주어진 규칙 코드들의 케이스만 뽑아 규칙별로 묶는다. 반환 순서는 {@code ruleCodes}가 준
     * 순서를 따르고, 케이스가 하나도 없는 규칙은 빠진다.
     */
    static Map<String, List<Map<String, String>>> byRuleCode(Collection<String> ruleCodes) throws IOException {
        List<Map<String, String>> all = rows();
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (String ruleCode : ruleCodes) {
            List<Map<String, String>> cases = all.stream()
                    .filter(row -> ruleCode.equals(row.get("ruleCode")))
                    .toList();
            if (!cases.isEmpty()) {
                grouped.put(ruleCode, cases);
            }
        }
        return grouped;
    }

    /** 따옴표 안의 쉼표·줄바꿈과 이중 따옴표를 살리는 테스트용 RFC4180 리더. */
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
        if (quoted) {
            throw new IllegalStateException("Unterminated CSV quote");
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
