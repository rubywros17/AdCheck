package com.adcheck.analysis.service;

import com.adcheck.product.domain.MatchMethod;
import com.adcheck.product.domain.MatchStatus;
import com.adcheck.product.service.OfficialFunctionReadModel;
import com.adcheck.product.service.ProductIngredientReadModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmedIngredientAssemblerTest {

    private final ConfirmedIngredientAssembler assembler = new ConfirmedIngredientAssembler();

    @Test
    void joinsOnIngredientMasterIdNotListPosition() {
        // 의도적으로 두 리스트 길이를 다르게 만든다: officialFunctions가 productIngredients보다
        // 많다(원료 10에 공식 기능성 2건) — 위치 기반 결합(zip)이었다면 인덱스가 어긋났을 상황.
        List<ProductIngredientReadModel> productIngredients = List.of(
                new ProductIngredientReadModel(10L, "루테인", "루테인 20mg", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED),
                new ProductIngredientReadModel(20L, "비타민C", "비타민C", MatchMethod.NORMALIZED_NAME, MatchStatus.MATCHED)
        );
        List<OfficialFunctionReadModel> officialFunctions = List.of(
                new OfficialFunctionReadModel(10L, "루테인", "눈 건강에 도움을 줄 수 있음",
                        OfficialFunctionReadModel.SourceType.FUNCTIONAL, "제2020-1호", "individual"),
                new OfficialFunctionReadModel(10L, "루테인", "황반색소 밀도 유지에 도움을 줄 수 있음",
                        OfficialFunctionReadModel.SourceType.FUNCTIONAL, "제2020-2호", "individual")
        );

        ConfirmedIngredientAssembler.Assembled assembled = assembler.assemble(productIngredients, officialFunctions);

        assertThat(assembled.confirmedIngredients()).hasSize(2);
        assertThat(assembled.confirmedIngredients())
                .extracting(ConfirmedIngredient::standardName)
                .containsExactly("루테인", "비타민C");

        // 비타민C(20L)는 officialFunctions에 없으므로 0건이 정상 — "확정 원료 수 == OfficialFunction
        // 수"를 가정하지 않는다는 규칙을 검증한다.
        assertThat(assembled.officialFunctions()).hasSize(2);
        assertThat(assembled.officialFunctions())
                .extracting(OfficialFunction::ingredientCode)
                .containsOnly("루테인");
    }

    @Test
    void usesProductIngredientCanonicalNameAsSingleSourceOfTruth() {
        List<ProductIngredientReadModel> productIngredients = List.of(
                new ProductIngredientReadModel(1L, "정식 표준명", "raw", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED)
        );
        // canonicalName이 다른 값("다른표기")이어도 OfficialFunctionReadModel 쪽 값은 무시하고
        // productIngredients에서 뽑은 "정식 표준명"을 그대로 써야 한다.
        List<OfficialFunctionReadModel> officialFunctions = List.of(
                new OfficialFunctionReadModel(1L, "다른표기", "기능성 문구",
                        OfficialFunctionReadModel.SourceType.NOTIFIED, null, "notice")
        );

        ConfirmedIngredientAssembler.Assembled assembled = assembler.assemble(productIngredients, officialFunctions);

        assertThat(assembled.officialFunctions()).hasSize(1);
        assertThat(assembled.officialFunctions().getFirst().ingredientCode()).isEqualTo("정식 표준명");
    }

    @Test
    void skipsOfficialFunctionWhenIngredientMasterIdIsNotAmongConfirmedIngredients() {
        List<ProductIngredientReadModel> productIngredients = List.of(
                new ProductIngredientReadModel(1L, "루테인", "루테인", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED)
        );
        List<OfficialFunctionReadModel> officialFunctions = List.of(
                new OfficialFunctionReadModel(999L, "알수없음", "문구",
                        OfficialFunctionReadModel.SourceType.FUNCTIONAL, "제9999호", "individual")
        );

        ConfirmedIngredientAssembler.Assembled assembled = assembler.assemble(productIngredients, officialFunctions);

        assertThat(assembled.confirmedIngredients()).hasSize(1);
        assertThat(assembled.officialFunctions()).isEmpty();
    }

    @Test
    void mostIngredientsHaveNoOfficialFunctionAndThatIsNormal() {
        List<ProductIngredientReadModel> productIngredients = List.of(
                new ProductIngredientReadModel(1L, "원료A", "원료A", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED),
                new ProductIngredientReadModel(2L, "원료B", "원료B", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED),
                new ProductIngredientReadModel(3L, "원료C", "원료C", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED)
        );

        ConfirmedIngredientAssembler.Assembled assembled = assembler.assemble(productIngredients, List.of());

        assertThat(assembled.confirmedIngredients()).hasSize(3);
        assertThat(assembled.officialFunctions()).isEmpty();
    }
}
