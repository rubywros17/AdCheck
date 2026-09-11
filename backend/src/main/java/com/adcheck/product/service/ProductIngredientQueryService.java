package com.adcheck.product.service;

import com.adcheck.product.domain.IngredientMaster;
import com.adcheck.product.domain.Product;
import com.adcheck.product.domain.ProductIngredientMatch;
import com.adcheck.product.repository.ProductIngredientMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ProductIngredientQueryService {

    private final ProductIngredientMatchRepository productIngredientMatchRepository;

    public ProductIngredientQueryService(
            ProductIngredientMatchRepository productIngredientMatchRepository
    ) {
        this.productIngredientMatchRepository = productIngredientMatchRepository;
    }

    public ProductIngredientQueryResult findByProduct(Product product) {
        Long productId = product == null ? null : product.getId();
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("확정된 Product와 ID가 필요합니다.");
        }

        List<ProductIngredientMatch> matches =
                productIngredientMatchRepository.findAllByProductId(productId);
        Map<Long, ProductIngredientReadModel> ingredientsByMasterId = new LinkedHashMap<>();

        for (ProductIngredientMatch match : matches) {
            if (match == null) {
                continue;
            }

            IngredientMaster ingredientMaster = match.getIngredientMaster();
            if (ingredientMaster == null || ingredientMaster.getId() == null) {
                continue;
            }

            ingredientsByMasterId.putIfAbsent(
                    ingredientMaster.getId(),
                    new ProductIngredientReadModel(
                            ingredientMaster.getId(),
                            ingredientMaster.getStandardName(),
                            match.getRawText(),
                            match.getMatchMethod(),
                            match.getMatchStatus()
                    )
            );
        }

        return ProductIngredientQueryResult.from(List.copyOf(ingredientsByMasterId.values()));
    }
}
