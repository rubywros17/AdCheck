package com.adcheck.product.repository;

import com.adcheck.product.domain.ProductIngredientMatch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductIngredientMatchRepository
        extends JpaRepository<ProductIngredientMatch, Long> {

    @EntityGraph(attributePaths = "ingredientMaster")
    List<ProductIngredientMatch> findAllByProductId(Long productId);
}
