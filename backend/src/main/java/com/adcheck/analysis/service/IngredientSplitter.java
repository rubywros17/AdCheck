package com.adcheck.analysis.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 괄호 깊이를 추적해서 원료 문자열 1개 필드를 여러 원료 항목으로 분리한다.
 *
 * <p>functionalIngredientRaw 필드는 "정제어유(EPA 18%이상, DHA 12%이상)"처럼
 * 괄호 안에 콤마가 들어있는 경우와, "산화아연(고시형), 비타민C(고시형)"처럼
 * 괄호 밖 콤마로 여러 원료를 나열한 경우가 섞여 있어서 단순 split(",")으로는
 * 전자를 반토막 내버린다. (prototype/ingredient_matching/parse_ingredients.py 포팅)
 *
 * <p>실제 상세페이지 텍스트(LLM으로 추출한 원료표시)에는 "1,200 mg", "3,000 mg"처럼
 * 괄호 밖에서 콤마가 천 단위 구분자로도 쓰이는데, 이 콤마의 앞뒤가 둘 다 숫자면
 * 항목 구분자가 아니라 숫자 표기의 일부로 보고 쪼개지 않는다.
 */
final class IngredientSplitter {

    private IngredientSplitter() {
    }

    record SplitResult(List<String> items, boolean parsed) {
    }

    /**
     * 괄호 깊이를 추적해서, 괄호 밖의 콤마만 구분자로 사용한다.
     *
     * <p>괄호 짝이 안 맞으면(원본 데이터 잘림 등) 쪼개지 않고 원문 전체를 단일
     * 항목으로 반환하며 parsed=false로 표시한다 — 잘못 쪼개서 엉뚱한 원료로
     * 오매칭되는 것보다 안전하다.
     */
    static SplitResult split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SplitResult(List.of(), true);
        }

        if (!parenBalanced(raw)) {
            return new SplitResult(List.of(raw.strip()), false);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                buf.append(ch);
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
                buf.append(ch);
            } else if (ch == ',' && depth == 0 && !isThousandsSeparator(raw, i)) {
                parts.add(buf.toString().strip());
                buf.setLength(0);
            } else {
                buf.append(ch);
            }
        }
        if (!buf.isEmpty()) {
            parts.add(buf.toString().strip());
        }

        List<String> filtered = parts.stream().filter(p -> !p.isEmpty()).toList();
        return new SplitResult(filtered, true);
    }

    /** 콤마 바로 앞뒤가 둘 다 숫자면(예: "1,200") 천 단위 구분자로 보고 항목 구분자로 취급하지 않는다. */
    private static boolean isThousandsSeparator(String raw, int commaIndex) {
        boolean beforeIsDigit = commaIndex > 0 && Character.isDigit(raw.charAt(commaIndex - 1));
        boolean afterIsDigit = commaIndex + 1 < raw.length() && Character.isDigit(raw.charAt(commaIndex + 1));
        return beforeIsDigit && afterIsDigit;
    }

    private static boolean parenBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }
}
