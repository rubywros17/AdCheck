package com.adcheck.analysis.repository;

import com.adcheck.product.domain.ProductIngredientMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductIngredientMatchRepository 
        extends JpaRepository<ProductIngredientMatch, Long> {

    List<ProductIngredientMatch> findAllByProductId(Long productId);

}
