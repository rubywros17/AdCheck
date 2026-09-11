package com.adcheck.product.service;

import com.adcheck.product.domain.IngredientMaster;
import com.adcheck.product.domain.MatchMethod;
import com.adcheck.product.domain.MatchStatus;
import com.adcheck.product.domain.Product;
import com.adcheck.product.domain.ProductIngredientMatch;
import com.adcheck.product.repository.ProductIngredientMatchRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProductIngredientQueryServiceTest {

    private final ProductIngredientMatchRepository repository =
            mock(ProductIngredientMatchRepository.class);
    private final ProductIngredientQueryService service =
            new ProductIngredientQueryService(repository);

    @Test
    void returnsIngredientsFromPrecomputedMatches() {
        Product product = product(10L);
        ProductIngredientMatch first = match(
                ingredient(101L, "루테인"),
                "루테인",
                MatchMethod.SYNONYM_EXACT,
                MatchStatus.MATCHED
        );
        ProductIngredientMatch second = match(
                ingredient(102L, "아연"),
                "산화아연",
                MatchMethod.NORMALIZED_NAME,
                MatchStatus.MATCHED
        );
        when(repository.findAllByProductId(10L)).thenReturn(List.of(first, second));

        ProductIngredientQueryResult result = service.findByProduct(product);

        assertAll(
                () -> assertThat(result.fallbackRequired()).isFalse(),
                () -> assertThat(result.ingredients()).hasSize(2),
                () -> assertThat(result.ingredients().get(0))
                        .extracting(
                                ProductIngredientReadModel::ingredientMasterId,
                                ProductIngredientReadModel::canonicalName,
                                ProductIngredientReadModel::rawText,
                                ProductIngredientReadModel::matchMethod,
                                ProductIngredientReadModel::matchStatus
                        )
                        .containsExactly(101L, "루테인", "루테인", MatchMethod.SYNONYM_EXACT, MatchStatus.MATCHED),
                () -> assertThat(result.ingredients().get(1).ingredientMasterId()).isEqualTo(102L)
        );
    }

    @Test
    void removesDuplicateIngredientMastersAndKeepsFirstOccurrence() {
        Product product = product(20L);
        IngredientMaster ingredient = ingredient(201L, "홍삼");
        ProductIngredientMatch first = match(
                ingredient,
                "홍삼농축액",
                MatchMethod.SYNONYM_EXACT,
                MatchStatus.MATCHED
        );
        ProductIngredientMatch duplicate = match(
                ingredient,
                "홍삼 추출물",
                MatchMethod.NORMALIZED_NAME,
                MatchStatus.MATCHED
        );
        when(repository.findAllByProductId(20L)).thenReturn(List.of(first, duplicate));

        ProductIngredientQueryResult result = service.findByProduct(product);

        assertThat(result.ingredients()).singleElement().satisfies(found -> {
            assertThat(found.ingredientMasterId()).isEqualTo(201L);
            assertThat(found.rawText()).isEqualTo("홍삼농축액");
            assertThat(found.matchMethod()).isEqualTo(MatchMethod.SYNONYM_EXACT);
        });
    }

    @Test
    void requiresFallbackWhenProductHasNoPrecomputedMatch() {
        Product product = product(30L);
        when(repository.findAllByProductId(30L)).thenReturn(List.of());

        ProductIngredientQueryResult result = service.findByProduct(product);

        assertAll(
                () -> assertThat(result.ingredients()).isEmpty(),
                () -> assertThat(result.fallbackRequired()).isTrue()
        );
    }

    @Test
    void skipsMatchWithoutIngredientMaster() {
        Product product = product(40L);
        ProductIngredientMatch invalidMatch = mock(ProductIngredientMatch.class);
        when(repository.findAllByProductId(40L)).thenReturn(List.of(invalidMatch));

        ProductIngredientQueryResult result = service.findByProduct(product);

        assertAll(
                () -> assertThat(result.ingredients()).isEmpty(),
                () -> assertThat(result.fallbackRequired()).isTrue()
        );
    }

    @Test
    void rejectsNullProductOrProductWithoutId() {
        Product productWithoutId = mock(Product.class);

        assertAll(
                () -> assertThatThrownBy(() -> service.findByProduct(null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("확정된 Product와 ID가 필요합니다."),
                () -> assertThatThrownBy(() -> service.findByProduct(productWithoutId))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("확정된 Product와 ID가 필요합니다.")
        );
    }

    @Test
    void onlyQueriesPrecomputedMatchesWithoutRuntimeMatching() {
        Product product = product(50L);
        when(repository.findAllByProductId(50L)).thenReturn(List.of());

        service.findByProduct(product);

        verify(repository).findAllByProductId(50L);
        verifyNoMoreInteractions(repository);
    }

    private Product product(Long id) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        return product;
    }

    private IngredientMaster ingredient(Long id, String standardName) {
        IngredientMaster ingredient = mock(IngredientMaster.class);
        when(ingredient.getId()).thenReturn(id);
        when(ingredient.getStandardName()).thenReturn(standardName);
        return ingredient;
    }

    private ProductIngredientMatch match(
            IngredientMaster ingredient,
            String rawText,
            MatchMethod matchMethod,
            MatchStatus matchStatus
    ) {
        ProductIngredientMatch match = mock(ProductIngredientMatch.class);
        when(match.getIngredientMaster()).thenReturn(ingredient);
        when(match.getRawText()).thenReturn(rawText);
        when(match.getMatchMethod()).thenReturn(matchMethod);
        when(match.getMatchStatus()).thenReturn(matchStatus);
        return match;
    }
}
