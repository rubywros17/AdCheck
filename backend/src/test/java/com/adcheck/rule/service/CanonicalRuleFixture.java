package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.util.ReflectionTestUtils;

/** com.adcheck.rule.service 밖(예: com.adcheck.analysis.service의 AiRuleEvaluator 테스트)에서도 재사용하도록 public. */
public final class CanonicalRuleFixture {
    private CanonicalRuleFixture() { }

    public static Rule rule(String code) {
        try (var input = CanonicalRuleFixture.class.getResourceAsStream("/db/migration/data/rules_v0.1.csv")) {
            if (input == null) throw new IllegalStateException("Canonical CSV missing");
            var rows = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", ""));
            for (var row : rows) {
                if (!row.getFirst().equals(code)) continue;
                var ctor = Rule.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                Rule rule = ctor.newInstance();
                String[] fields = {"ruleCode", "scopeType", null, "expressionType", "candidateExamples",
                        "judgmentCategory", "applicationConditions", "exceptions", "requiredEvidence",
                        "severity", null, null, "ruleVersion", "reviewStatus"};
                ReflectionTestUtils.setField(rule, "id", (long) rows.indexOf(row));
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] == null) continue;
                    String value = row.get(i);
                    if (value.isBlank() || (fields[i].equals("requiredEvidence") && value.equals("null"))) value = null;
                    ReflectionTestUtils.setField(rule, fields[i], value);
                }
                return rule;
            }
            throw new IllegalArgumentException("Unknown canonical rule: " + code);
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // Test-only RFC4180 reader: quoted commas/newlines and doubled quotes are retained.
    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append(c); i++; }
                else quoted = !quoted;
            } else if (!quoted && (c == ',' || c == '\n' || c == '\r')) {
                row.add(field.toString()); field.setLength(0);
                if (c != ',') {
                    rows.add(row); row = new ArrayList<>();
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                }
            } else field.append(c);
        }
        if (quoted) throw new IllegalStateException("Unterminated CSV quote");
        if (!field.isEmpty() || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }
}
