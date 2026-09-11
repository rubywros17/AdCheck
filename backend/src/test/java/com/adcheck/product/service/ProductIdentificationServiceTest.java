package com.adcheck.product.service;

import com.adcheck.product.domain.Product;
import com.adcheck.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.adcheck.product.service.ProductIdentificationResult.MatchType.EXACT_NAME_COMPANY;
import static com.adcheck.product.service.ProductIdentificationResult.MatchType.EXACT_REPORT_NO;
import static com.adcheck.product.service.ProductIdentificationResult.MatchType.NONE;
import static com.adcheck.product.service.ProductIdentificationResult.Status.AMBIGUOUS;
import static com.adcheck.product.service.ProductIdentificationResult.Status.FOUND;
import static com.adcheck.product.service.ProductIdentificationResult.Status.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductIdentificationServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductIdentificationService service = new ProductIdentificationService(productRepository);

    @Test
    void findsProductByExactReportNo() {
        Product product = mock(Product.class);
        when(productRepository.findByProductReportNo("200400000001"))
                .thenReturn(Optional.of(product));

        ProductIdentificationResult result = service.identify(
                new ProductIdentificationCandidate("200400000001", "후보 상품", "후보 회사")
        );

        assertAll(
                () -> assertThat(result.status()).isEqualTo(FOUND),
                () -> assertThat(result.product()).isSameAs(product),
                () -> assertThat(result.candidates()).isEmpty(),
                () -> assertThat(result.matchType()).isEqualTo(EXACT_REPORT_NO)
        );
        verify(productRepository, never())
                .findAllByProductNameAndCompanyName("후보 상품", "후보 회사");
    }

    @Test
    void doesNotFallbackWhenProvidedReportNoDoesNotExist() {
        when(productRepository.findByProductReportNo("없는-신고번호"))
                .thenReturn(Optional.empty());

        ProductIdentificationResult result = service.identify(
                new ProductIdentificationCandidate("없는-신고번호", "존재하는 상품", "존재하는 회사")
        );

        assertNotFound(result);
        verify(productRepository, never())
                .findAllByProductNameAndCompanyName("존재하는 상품", "존재하는 회사");
    }

    @Test
    void findsSingleCandidateByExactNameAndCompany() {
        Product product = mock(Product.class);
        when(productRepository.findAllByProductNameAndCompanyName("루테인 제품", "애드체크 식품"))
                .thenReturn(List.of(product));

        ProductIdentificationResult result = service.identify(
                new ProductIdentificationCandidate(" ", "루테인 제품", "애드체크 식품")
        );

        assertAll(
                () -> assertThat(result.status()).isEqualTo(FOUND),
                () -> assertThat(result.product()).isSameAs(product),
                () -> assertThat(result.candidates()).isEmpty(),
                () -> assertThat(result.matchType()).isEqualTo(EXACT_NAME_COMPANY)
        );
    }

    @Test
    void returnsAmbiguousForMultipleExactNameAndCompanyCandidates() {
        Product first = mock(Product.class);
        Product second = mock(Product.class);
        when(productRepository.findAllByProductNameAndCompanyName("중복 상품", "동일 회사"))
                .thenReturn(List.of(first, second));

        ProductIdentificationResult result = service.identify(
                new ProductIdentificationCandidate(null, "중복 상품", "동일 회사")
        );

        assertAll(
                () -> assertThat(result.status()).isEqualTo(AMBIGUOUS),
                () -> assertThat(result.product()).isNull(),
                () -> assertThat(result.candidates()).containsExactly(first, second),
                () -> assertThat(result.matchType()).isEqualTo(EXACT_NAME_COMPANY)
        );
    }

    @Test
    void returnsNotFoundWhenExactNameAndCompanyHaveNoCandidate() {
        when(productRepository.findAllByProductNameAndCompanyName("없는 상품", "없는 회사"))
                .thenReturn(List.of());

        ProductIdentificationResult result = service.identify(
                new ProductIdentificationCandidate(null, "없는 상품", "없는 회사")
        );

        assertNotFound(result);
    }

    @Test
    void returnsNotFoundForNullOrBlankInputWithoutRepositoryLookup() {
        ProductIdentificationResult nullCandidate = service.identify(null);
        ProductIdentificationResult blankCandidate = service.identify(
                new ProductIdentificationCandidate(" ", " ", null)
        );

        assertAll(
                () -> assertNotFound(nullCandidate),
                () -> assertNotFound(blankCandidate)
        );
        verifyNoInteractions(productRepository);
    }

    @Test
    void doesNotApplyFuzzyMatchingToNameAndCompany() {
        when(productRepository.findAllByProductNameAndCompanyName("루테인 플러스", "애드체크식품"))
                .thenReturn(List.of());

        ProductIdentificationResult result = service.identify(
                new ProductIdentificationCandidate(null, "루테인 플러스", "애드체크식품")
        );

        assertNotFound(result);
        verify(productRepository)
                .findAllByProductNameAndCompanyName("루테인 플러스", "애드체크식품");
    }

    private void assertNotFound(ProductIdentificationResult result) {
        assertAll(
                () -> assertThat(result.status()).isEqualTo(NOT_FOUND),
                () -> assertThat(result.product()).isNull(),
                () -> assertThat(result.candidates()).isEmpty(),
                () -> assertThat(result.matchType()).isEqualTo(NONE)
        );
    }
}
