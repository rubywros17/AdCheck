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
     * 함량 표기 뒤에서도 항목을 끊는다. 상세페이지 원료표시는 콤마 없이 "락토페린(우유정제
     * 단백질) 270mg/일 락토페린(우유정제단백질) 270mg/일 ..."처럼 같은 문구가 디자인상 반복되는
     * 경우가 있는데(2026-09-23 실측), 콤마만 구분자로 쓰면 이게 통째로 한 항목이 되어 사전에
     * 등록된 "락토페린(우유정제단백질)"과도 매칭되지 않는다. 숫자+단위(+선택적 "/일" 같은 꼬리)
     * 바로 뒤를 경계로 본다.
     */
    private static final java.util.regex.Pattern DOSAGE_BOUNDARY = java.util.regex.Pattern.compile(
            "(?<=[0-9])\\s*(?:mg|g|㎎|㎍|μg|ug|iu|kcal|%)\\s*(?:/\\s*\\S+)?\\s+(?=\\S)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

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

        List<String> filtered = parts.stream()
                .flatMap(part -> splitOnDosage(part).stream())
                .filter(p -> !p.isEmpty())
                .toList();
        return new SplitResult(filtered, true);
    }

    /**
     * 콤마로 나눈 항목을 함량 표기 경계에서 한 번 더 끊는다. 괄호 안은 건드리지 않기 위해
     * 괄호 깊이가 0인 구간에서만 적용한다 — "정제어유(EPA 18%이상, DHA 12%이상)"처럼 괄호
     * 안에 함량이 들어 있는 경우를 반토막 내면 안 된다.
     */
    private static List<String> splitOnDosage(String part) {
        if (part.isBlank() || hasParenthesis(part) && !parenBalanced(part)) {
            return List.of(part);
        }
        // 괄호가 포함된 항목은 괄호 밖 구간만 경계로 쓰기 위해, 괄호 안을 임시로 가려서 위치를 찾는다.
        String masked = maskParenthesized(part);
        java.util.regex.Matcher matcher = DOSAGE_BOUNDARY.matcher(masked);
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (matcher.find()) {
            pieces.add(part.substring(start, matcher.end()).strip());
            start = matcher.end();
        }
        if (pieces.isEmpty()) {
            return List.of(part);
        }
        pieces.add(part.substring(start).strip());
        return pieces.stream().filter(p -> !p.isEmpty()).toList();
    }

    private static boolean hasParenthesis(String s) {
        return s.indexOf('(') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0;
    }

    /** 괄호 안 문자를 같은 길이의 placeholder로 바꿔, 괄호 밖 위치만 정규식에 걸리게 한다. */
    private static String maskParenthesized(String s) {
        StringBuilder masked = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                masked.append('_');
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
                masked.append('_');
            } else {
                masked.append(depth > 0 ? '_' : ch);
            }
        }
        return masked.toString();
    }

    /**
     * 괄호 짝이 안 맞아 {@link #split}이 통째로 돌려준 문자열을, 짝 없는 괄호를 지운 뒤 다시
     * 쪼개본다.
     *
     * <p>원본 경로를 바꾸지 않고 <b>매칭이 실패한 뒤에만</b> 부르는 보정용이다. 짝이 안 맞으면
     * 쪼개지 않는 건 "잘못 쪼개서 오매칭되는 것보다 안전하다"는 의도된 설계이고 그대로 둔다 —
     * 다만 실측에서 버려진 원료표 후보 5건 중 3건이 이 경우였고(2026-09-23), 원인이 원료 표기가
     * 이상해서가 아니라 <b>상세페이지 텍스트가 중간에 잘려서</b>였다. 예: "…제이인산칼슘),
     * 비타민B12혼합제제(비타민B" — 앞의 여는 괄호와 뒤의 닫는 괄호가 각각 잘려나갔다.
     * 이런 문자열도 콤마로 끊으면 "니코틴산아미드"·"판토텐산칼슘"처럼 멀쩡한 항목을 건질 수 있다.
     *
     * @return 보정해서 쪼갠 항목들. 원래 짝이 맞았거나 쪼갤 게 없으면 빈 리스트.
     */
    static List<String> splitLenient(String raw) {
        if (raw == null || raw.isBlank() || parenBalanced(raw)) {
            return List.of();
        }
        SplitResult result = split(dropUnbalancedBrackets(raw));
        return result.items().size() > 1 ? result.items() : List.of();
    }

    /** 짝이 없는 괄호만 골라 지운다. 짝이 맞는 괄호는 그대로 둬서 괄호 안 콤마는 계속 보호한다. */
    private static String dropUnbalancedBrackets(String raw) {
        boolean[] drop = new boolean[raw.length()];
        java.util.Deque<Integer> openStack = new java.util.ArrayDeque<>();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                openStack.push(i);
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (openStack.isEmpty()) {
                    drop[i] = true;
                } else {
                    openStack.pop();
                }
            }
        }
        openStack.forEach(index -> drop[index] = true);

        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            if (!drop[i]) {
                cleaned.append(raw.charAt(i));
            }
        }
        return cleaned.toString();
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
