package com.adcheck.product.service;

import com.adcheck.product.domain.FunctionalIngredient;
import com.adcheck.product.domain.IngredientMaster;
import com.adcheck.product.domain.NotifiedIngredient;
import com.adcheck.product.repository.FunctionalIngredientRepository;
import com.adcheck.product.repository.NotifiedIngredientRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.adcheck.product.service.OfficialFunctionReadModel.SourceType.FUNCTIONAL;
import static com.adcheck.product.service.OfficialFunctionReadModel.SourceType.NOTIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OfficialFunctionQueryServiceTest {

    private final FunctionalIngredientRepository functionalRepository =
            mock(FunctionalIngredientRepository.class);
    private final NotifiedIngredientRepository notifiedRepository =
            mock(NotifiedIngredientRepository.class);
    private final OfficialFunctionQueryService service =
            new OfficialFunctionQueryService(functionalRepository, notifiedRepository);

    @Test
    void returnsFunctionalOfficialFunction() {
        FunctionalIngredient functional = functional(
                101L, "루테인", "제2020-1호", "눈 건강에 도움을 줄 수 있음", "식품안전나라"
        );
        stubBatch(List.of(101L), List.of(functional), List.of());

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(101L));

        assertThat(result).singleElement().satisfies(found -> assertAll(
                () -> assertThat(found.ingredientMasterId()).isEqualTo(101L),
                () -> assertThat(found.canonicalName()).isEqualTo("루테인"),
                () -> assertThat(found.officialFunctionText()).isEqualTo("눈 건강에 도움을 줄 수 있음"),
                () -> assertThat(found.sourceType()).isEqualTo(FUNCTIONAL),
                () -> assertThat(found.recognitionNo()).isEqualTo("제2020-1호"),
                () -> assertThat(found.sourceName()).isEqualTo("식품안전나라")
        ));
    }

    @Test
    void returnsNotifiedOfficialFunction() {
        NotifiedIngredient notified = notified(
                201L, "아연", "정상적인 면역기능에 필요", "건강기능식품 공전"
        );
        stubBatch(List.of(201L), List.of(), List.of(notified));

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(201L));

        assertThat(result).singleElement().satisfies(found -> assertAll(
                () -> assertThat(found.ingredientMasterId()).isEqualTo(201L),
                () -> assertThat(found.canonicalName()).isEqualTo("아연"),
                () -> assertThat(found.officialFunctionText()).isEqualTo("정상적인 면역기능에 필요"),
                () -> assertThat(found.sourceType()).isEqualTo(NOTIFIED),
                () -> assertThat(found.recognitionNo()).isNull(),
                () -> assertThat(found.sourceName()).isEqualTo("건강기능식품 공전")
        ));
    }

    @Test
    void keepsFunctionalAndNotifiedResultsForSameIngredient() {
        FunctionalIngredient functional = functional(
                301L, "홍삼", "제2021-2호", "면역력 증진에 도움을 줄 수 있음", "개별인정"
        );
        NotifiedIngredient notified = notified(
                301L, "홍삼", "피로 개선에 도움을 줄 수 있음", "건강기능식품 공전"
        );
        stubBatch(List.of(301L), List.of(functional), List.of(notified));

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(301L));

        assertThat(result).extracting(OfficialFunctionReadModel::sourceType)
                .containsExactly(FUNCTIONAL, NOTIFIED);
    }

    @Test
    void returnsEmptyListWhenOfficialFunctionDataDoesNotExist() {
        stubBatch(List.of(401L), List.of(), List.of());

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(401L));

        assertThat(result).isEmpty();
    }

    @Test
    void loadsOfficialFunctionsForMultipleIngredientMastersWithTwoBatchQueries() {
        FunctionalIngredient first = functional(
                501L, "루테인", "제2020-1호", "눈 건강에 도움을 줄 수 있음", "개별인정"
        );
        FunctionalIngredient second = functional(
                502L, "홍삼", "제2021-2호", "면역력 증진에 도움을 줄 수 있음", "개별인정"
        );
        NotifiedIngredient third = notified(
                503L, "아연", "정상적인 면역기능에 필요", "건강기능식품 공전"
        );
        List<Long> ids = List.of(501L, 502L, 503L);
        stubBatch(ids, List.of(first, second), List.of(third));

        List<OfficialFunctionReadModel> result = service.findAllByIngredientMasterIds(ids);

        assertThat(result).extracting(OfficialFunctionReadModel::ingredientMasterId)
                .containsExactly(501L, 502L, 503L);
        verify(functionalRepository).findAllByIngredientMaster_IdIn(ids);
        verify(notifiedRepository).findAllByIngredientMaster_IdIn(ids);
    }

    @Test
    void preservesNullAndBlankOfficialFunctionTextWithoutGeneratingFallbackText() {
        FunctionalIngredient nullText = functional(
                601L, "원료 A", "제2022-1호", null, "개별인정"
        );
        NotifiedIngredient blankText = notified(
                602L, "원료 B", "", "건강기능식품 공전"
        );
        stubBatch(List.of(601L, 602L), List.of(nullText), List.of(blankText));

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(601L, 602L));

        assertAll(
                () -> assertThat(result.get(0).officialFunctionText()).isNull(),
                () -> assertThat(result.get(1).officialFunctionText()).isEmpty()
        );
    }

    @Test
    void removesDuplicateRowsWithinSameSource() {
        FunctionalIngredient first = functional(
                701L, "루테인", "제2020-1호", "눈 건강에 도움을 줄 수 있음", "원본 A"
        );
        FunctionalIngredient duplicate = functional(
                701L, "루테인", "제2020-1호", "눈 건강에 도움을 줄 수 있음", "원본 B"
        );
        stubBatch(List.of(701L), List.of(first, duplicate), List.of());

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(701L));

        assertThat(result).singleElement().satisfies(found ->
                assertThat(found.sourceName()).isEqualTo("원본 A")
        );
    }

    @Test
    void doesNotMergeSameTextAcrossDifferentSourceTypes() {
        String sameText = "항산화에 도움을 줄 수 있음";
        FunctionalIngredient functional = functional(
                801L, "원료 C", "제2023-1호", sameText, "개별인정"
        );
        NotifiedIngredient notified = notified(
                801L, "원료 C", sameText, "건강기능식품 공전"
        );
        stubBatch(List.of(801L), List.of(functional), List.of(notified));

        List<OfficialFunctionReadModel> result =
                service.findAllByIngredientMasterIds(List.of(801L));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(OfficialFunctionReadModel::sourceType)
                .containsExactly(FUNCTIONAL, NOTIFIED);
    }

    @Test
    void returnsEmptyListForNullOrInvalidIngredientMasterIds() {
        assertAll(
                () -> assertThat(service.findAllByIngredientMasterIds(null)).isEmpty(),
                () -> assertThat(service.findAllByIngredientMasterIds(List.of(0L, -1L))).isEmpty()
        );
        verifyNoInteractions(functionalRepository, notifiedRepository);
    }

    private void stubBatch(
            List<Long> ids,
            List<FunctionalIngredient> functional,
            List<NotifiedIngredient> notified
    ) {
        when(functionalRepository.findAllByIngredientMaster_IdIn(ids)).thenReturn(functional);
        when(notifiedRepository.findAllByIngredientMaster_IdIn(ids)).thenReturn(notified);
    }

    private FunctionalIngredient functional(
            Long ingredientMasterId,
            String canonicalName,
            String recognitionNo,
            String officialFunctionText,
            String sourceName
    ) {
        IngredientMaster master = ingredientMaster(ingredientMasterId, canonicalName);
        FunctionalIngredient ingredient = mock(FunctionalIngredient.class);
        when(ingredient.getIngredientMaster()).thenReturn(master);
        when(ingredient.getRecognitionNo()).thenReturn(recognitionNo);
        when(ingredient.getOfficialFunctionRaw()).thenReturn(officialFunctionText);
        when(ingredient.getSourceName()).thenReturn(sourceName);
        return ingredient;
    }

    private NotifiedIngredient notified(
            Long ingredientMasterId,
            String canonicalName,
            String officialFunctionText,
            String sourceName
    ) {
        IngredientMaster master = ingredientMaster(ingredientMasterId, canonicalName);
        NotifiedIngredient ingredient = mock(NotifiedIngredient.class);
        when(ingredient.getIngredientMaster()).thenReturn(master);
        when(ingredient.getOfficialFunctionRaw()).thenReturn(officialFunctionText);
        when(ingredient.getSourceName()).thenReturn(sourceName);
        return ingredient;
    }

    private IngredientMaster ingredientMaster(Long id, String standardName) {
        IngredientMaster master = mock(IngredientMaster.class);
        when(master.getId()).thenReturn(id);
        when(master.getStandardName()).thenReturn(standardName);
        return master;
    }
}
