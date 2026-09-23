package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 분석에서 "원료표 후보에서 인식되는 원료가 하나도 없어 제외함"으로 버려진 문자열을 그대로
 * 넣어 분할 동작을 확인한다. 버려진 5건을 살펴보니 원인이 둘이었다(2026-09-23):
 *
 * <ul>
 *   <li><b>콤마가 없어 통째로 한 항목</b> — "락토페린(우유정제단백질) 270mg/일"이 디자인상 세 번
 *       반복되는데 구분자가 없어 전부 한 덩어리가 됐다.</li>
 *   <li><b>괄호 짝 불일치</b> — 상세페이지 텍스트가 중간에 잘려 "…제이인산칼슘), 비타민B12
 *       혼합제제(비타민B"처럼 여는/닫는 괄호가 각각 날아갔다. 이때 분할기는 "잘못 쪼개는 것보다
 *       안전하다"는 설계에 따라 통째로 한 항목을 돌려준다 — 이 동작 자체는 유지하고, 매칭이
 *       전부 실패했을 때만 {@link IngredientSplitter#splitLenient}로 한 번 더 시도한다.</li>
 * </ul>
 */
class IngredientSplitterRealCandidateTest {

    /** 실제 상세페이지에서 반복 노출돼 구분자 없이 이어진 케이스. */
    private static final String REPEATED_WITHOUT_COMMA =
            "락토페린(우유정제단백질) 270mg/일 락토페린(우유정제단백질) 270mg/일 락토페린(우유정제단백질) 270mg/일 락토페린";

    /** 텍스트가 잘려 괄호 짝이 깨진 케이스. */
    private static final String TRUNCATED_UNBALANCED =
            "니코틴산아미드, 판토텐산칼슘, 크씨슬추출물분말(독일산), 제비오틴, 제이인산칼슘), 비타민B12혼합제제(비타민B";

    @Test
    void 구분자가_없어도_함량_표기_뒤에서_항목을_끊는다() {
        IngredientSplitter.SplitResult result = IngredientSplitter.split(REPEATED_WITHOUT_COMMA);

        assertThat(result.parsed()).isTrue();
        assertThat(result.items())
                .containsExactly(
                        "락토페린(우유정제단백질) 270mg/일",
                        "락토페린(우유정제단백질) 270mg/일",
                        "락토페린(우유정제단백질) 270mg/일",
                        "락토페린");
    }

    @Test
    void 괄호_안_함량은_경계로_쓰지_않는다() {
        // "정제어유(EPA 18%이상, DHA 12%이상)"을 반토막 내면 안 된다.
        IngredientSplitter.SplitResult result =
                IngredientSplitter.split("정제어유(EPA 18%이상, DHA 12%이상)");

        assertThat(result.items()).containsExactly("정제어유(EPA 18%이상, DHA 12%이상)");
    }

    @Test
    void 괄호_짝이_깨지면_기존처럼_통째로_돌려준다() {
        IngredientSplitter.SplitResult result = IngredientSplitter.split(TRUNCATED_UNBALANCED);

        assertThat(result.parsed()).isFalse();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void 보정_분할은_짝_없는_괄호만_지우고_다시_쪼갠다() {
        assertThat(IngredientSplitter.splitLenient(TRUNCATED_UNBALANCED))
                .contains("니코틴산아미드", "판토텐산칼슘", "제비오틴");
    }

    @Test
    void 원래_짝이_맞는_문자열에는_보정_분할이_동작하지_않는다() {
        assertThat(IngredientSplitter.splitLenient("비타민C 500mg, 아연 10mg")).isEmpty();
    }
}
