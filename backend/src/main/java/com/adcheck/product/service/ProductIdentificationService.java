package com.adcheck.product.service;

import com.adcheck.product.domain.Product;
import com.adcheck.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductIdentificationService {

    private final ProductRepository productRepository;

    public ProductIdentificationService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public ProductIdentificationResult identify(ProductIdentificationCandidate candidate) {
        if (candidate == null) {
            return ProductIdentificationResult.notFound();
        }

        if (StringUtils.hasText(candidate.productReportNo())) {
            return productRepository.findByProductReportNo(candidate.productReportNo())
                    .map(product -> ProductIdentificationResult.found(
                            product,
                            ProductIdentificationResult.MatchType.EXACT_REPORT_NO
                    ))
                    .orElseGet(ProductIdentificationResult::notFound);
        }

        if (!StringUtils.hasText(candidate.productName())
                || !StringUtils.hasText(candidate.companyName())) {
            return ProductIdentificationResult.notFound();
        }

        List<Product> products = productRepository.findAllByProductNameAndCompanyName(
                candidate.productName(),
                candidate.companyName()
        );

        if (products.size() == 1) {
            return ProductIdentificationResult.found(
                    products.getFirst(),
                    ProductIdentificationResult.MatchType.EXACT_NAME_COMPANY
            );
        }
        if (products.size() > 1) {
            return ProductIdentificationResult.ambiguous(products);
        }
        return ProductIdentificationResult.notFound();
    }
}
