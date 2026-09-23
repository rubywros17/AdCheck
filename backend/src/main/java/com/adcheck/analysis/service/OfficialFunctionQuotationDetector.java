package com.adcheck.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 추출된 Claim이 <b>확정 원료의 공식 인정 기능성 문구를 그대로 옮긴 것</b>인지 판정한다.
 *
 * <p>배경(2026-09-23): 건강기능식품 상세페이지에는 판매자가 쓴 광고 카피와, 식약처가 인정한
 * 문구를 그대로 옮긴 법정 기능성 표시란이 섞여 있다. 뒤쪽은 검수 대상이 아니라 오히려 규정을
 * 지킨 표기인데 AI#1이 둘을 구분하지 못해 Claim으로 올라왔고, 위반이 아니니 규칙도 MATCHED를
 * 못 내고 REVIEW_REQUIRED로만 떨어져 "확인이 필요한 표현입니다." HIGH 카드가 됐다.
 *
 * <p>프롬프트에 겉모양 규칙("{@code [원료명]} 머리말이 붙은 줄")을 추가해 봤지만 절반만 통했다
 * — 표시란은 페이지마다 대괄호를 쓰기도 안 쓰기도, 표로 넣기도 문장으로 풀기도 한다. 겉모양을
 * 쫓는 대신, 우리가 이미 DB에 갖고 있는 공식 문구와 직접 대조한다.
 *
 * <h2>판정 방식 — 잔여물 검사</h2>
 * "공식 문구를 담고 있는가"가 아니라 <b>"공식 문구 말고 다른 말이 섞여 있는가"</b>를 본다.
 * Claim에서 공식 문구의 항목들과 상투구를 모두 빼고, 남은 게 하나도 없을 때만 인용으로 본다.
 *
 * <pre>
 *   "간 건강에 도움을 줄 수 있음"              → 잔여물 없음        → 인용
 *   "…항산화에 도움을 줄 수 있는 건강기능식품입니다" → 상투구뿐        → 인용
 *   "간 건강에 도움을 줄 수 있음 — 간경화까지 완치!" → "간경화","완치" → 인용 아님
 *   "간 건강에 도움! 완치!"                     → "완치"           → 인용 아님
 * </pre>
 *
 * <p><b>길이 비교를 쓰지 않는 이유</b>: 처음에는 "Claim이 공식 문구보다 현저히 길면 인용이
 * 아니다"로 잡으려 했는데, 과장을 짧게 덧붙이면("완치" 2자) 길이로는 잡히지 않는다. 실제로
 * {@code "간 건강에 도움! 완치!"}(13자)는 공식 문구(16자)보다 <b>짧다</b>.
 *
 * <p><b>틀리는 방향을 한쪽으로 몰아둔다</b>: 잔여물 허용치를 0자로 두고 상투구 목록만으로
 * 흡수하게 했다. 상투구 목록이 부족하면 인용을 못 알아보는 쪽(=지금과 동일하게 HIGH로 뜨는 쪽,
 * 나빠지지 않음)으로 틀리고, 위반을 인용으로 오판해 <b>걸러버리는</b> 쪽으로는 틀리지 않는다.
 * 후자는 검수 도구가 위반을 숨기는 것이라 성격이 전혀 다르다.
 */
@Component
public class OfficialFunctionQuotationDetector {

    /**
     * 공식 문구가 여러 기능을 나열할 때 쓰는 구분자. 같은 문구라도 DB는 {@code ･}(반각 가운뎃점),
     * 페이지는 {@code ·}나 쉼표를 쓰는 식으로 제각각이라 전부 같은 것으로 취급한다.
     */
    private static final String SEPARATORS = "[·･ㆍ∙•,、/]";

    /**
     * 기능 항목이 아니라 그 앞뒤에 늘 붙는 상투구. 잔여물에서 제거해 "실질적인 내용어"만 남긴다.
     * 긴 것부터 지워야 짧은 것이 긴 것의 일부를 먼저 갉아먹지 않는다(예: "에도움"이
     * "에도움을줄수있음"보다 먼저 지워지면 "을줄수있음"이 남는다).
     */
    private static final List<String> BOILERPLATE = List.of(
            "에도움을줄수있는건강기능식품입니다",
            "하는데도움을줄수있는건강기능식품입니다",
            "하는데도움을줄수있습니다",
            "하는데도움을줄수있음",
            "하는데도움을줄수있는",
            "하는데도움",
            "하는데",
            "사람에게",
            "에게",
            "에도움을줄수있습니다",
            "에도움을줄수있음",
            "에도움을줄수있는",
            "에도움을줄수있다",
            "에도움이될수있음",
            "에도움을줌",
            "에도움",
            "에필요한",
            "에필요",
            "에관여하는",
            "에관여",
            "데필요한",
            "데필요",
            "본제품은",
            "건강기능식품입니다",
            "건강기능식품",
            "기능성내용",
            "기능성원료",
            "입니다",
            "있음",
            "함유",
            "및",
            "와",
            "과",
            "등",
            "의",
            "을",
            "를",
            "이",
            "가",
            "은",
            "는");

    /**
     * 이 Claim이 확정 원료의 공식 기능성 문구를 그대로 옮긴 것인지 판정한다.
     *
     * @param claimText         AI#1이 뽑은 Claim 원문
     * @param officialFunctions 확정 원료의 공식 기능성 목록. {@code ingredientCode}에는
     *                          ingredientMasterId가 아니라 원료의 canonicalName이 들어 있다
     *                          ({@link ConfirmedIngredientAssembler} 참고) — 표시란이 원료명을
     *                          머리말로 달고 있는 경우 그 이름도 잔여물에서 지워야 해서 쓴다.
     * @return 하나라도 인용으로 판정되면 {@code true}
     */
    public boolean isQuotation(String claimText, List<OfficialFunction> officialFunctions) {
        if (claimText == null || claimText.isBlank() || officialFunctions == null || officialFunctions.isEmpty()) {
            return false;
        }
        String normalizedClaim = normalize(stripAnnotations(claimText));
        if (normalizedClaim.isEmpty()) {
            return false;
        }
        return officialFunctions.stream()
                .anyMatch(official -> isQuotationOf(normalizedClaim, official, officialFunctions));
    }

    private boolean isQuotationOf(
            String normalizedClaim,
            OfficialFunction official,
            List<OfficialFunction> allOfficialFunctions
    ) {
        if (official.functionText() == null || official.functionText().isBlank()) {
            return false;
        }
        List<String> items = functionItems(official.functionText());
        if (items.isEmpty()) {
            return false;
        }

        String remainder = normalizedClaim;
        int matchedItems = 0;
        // 긴 항목부터 지운다 — 짧은 항목이 긴 항목의 일부와 먼저 겹쳐 지워지는 것을 막는다.
        for (String item : items.stream().sorted((a, b) -> b.length() - a.length()).toList()) {
            if (remainder.contains(item)) {
                remainder = remainder.replace(item, "");
                matchedItems++;
            }
        }
        // 공식 문구와 거의 무관한데 항목 하나가 우연히 겹친 것을 인용으로 보지 않는다.
        if (matchedItems * 2 < items.size()) {
            return false;
        }

        // 표시란은 원료명을 머리말로 달고 있는 경우가 많다("[밀크씨슬추출물] 간 건강에…").
        // 그 이름은 광고성 내용어가 아니므로 잔여물에서 제외한다.
        for (OfficialFunction other : allOfficialFunctions) {
            if (other.ingredientCode() == null) {
                continue;
            }
            String name = normalize(other.ingredientCode());
            if (name.length() >= 2) {
                remainder = remainder.replace(name, "");
            }
        }
        for (String boilerplate : BOILERPLATE) {
            remainder = remainder.replace(boilerplate, "");
        }

        // 잔여물이 한 글자라도 남으면 인용으로 보지 않는다 — "완치"(2자)처럼 짧은 과장을
        // 허용치로 흘려보내지 않기 위해 0자 기준을 쓴다.
        return remainder.isEmpty();
    }

    /**
     * 공식 문구에는 기능 내용이 아닌 부속 표기가 섞여 있다 — 갈래 기호 {@code (가)/(나)}, 번호
     * 매김 {@code 1) 2)}, 등급 주석 {@code (기타Ⅱ)}·{@code (생리활성기능 2등급)}, 각주 {@code [1)}.
     * 페이지가 공식 문구를 옮길 때 이것들도 같이 옮겨오므로 <b>Claim과 공식 문구 양쪽에서 똑같이</b>
     * 지워야 한다. 한쪽만 지우면 잔여물로 남아 인용을 못 알아본다(실측: 이 처리를 공식 문구에만
     * 적용했을 때 인식률 90.5%, 못 알아본 308건의 대부분이 이 표기들이었다).
     */
    private String stripAnnotations(String text) {
        return text.replaceAll("\\([가-힣]\\)", " ")
                .replaceAll("(?<![0-9])[0-9]{1,2}\\)", " ")
                .replaceAll("\\[[0-9]+\\)?", " ")
                .replaceAll("\\([^)]*등급[^)]*\\)", " ")
                .replaceAll("\\(기타[^)]*\\)", " ")
                .replaceAll("\\(생리활성[^)]*\\)", " ")
                .replaceAll("\\(질병발생[^)]*\\)", " ");
    }

    /** 공식 문구를 기능 항목 단위로 쪼갠다. 꼬리 상투구("…에 도움을 줄 수 있음")는 먼저 떼어낸다. */
    private List<String> functionItems(String functionText) {
        return java.util.Arrays.stream(stripAnnotations(functionText).split(SEPARATORS))
                .map(this::normalize)
                .map(this::stripTrailingBoilerplate)
                .filter(item -> item.length() >= 2)
                .toList();
    }

    private String stripTrailingBoilerplate(String item) {
        String result = item;
        boolean changed = true;
        // 꼬리 상투구가 여러 겹인 경우("…에도움을줄수있는건강기능식품입니다")를 위해 더 지울 게
        // 없을 때까지 반복한다.
        while (changed) {
            changed = false;
            for (String boilerplate : BOILERPLATE) {
                if (result.length() > boilerplate.length() && result.endsWith(boilerplate)) {
                    result = result.substring(0, result.length() - boilerplate.length());
                    changed = true;
                }
            }
        }
        return result;
    }

    /** 공백·구두점·괄호를 없애고 소문자로 맞춘다 — 같은 문구의 표기 차이를 흡수하기 위해서다. */
    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}()\\[\\]{}〔〕「」『』～~—–-]", "")
                .replaceAll(SEPARATORS, "");
    }
}
