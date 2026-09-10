package com.adcheck.product.repository;

import com.adcheck.product.domain.IngredientSynonym;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngredientSynonymRepository extends JpaRepository<IngredientSynonym, Long> {
}
