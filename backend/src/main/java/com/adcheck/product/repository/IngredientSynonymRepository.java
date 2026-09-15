package com.adcheck.product.repository;

import com.adcheck.product.domain.IngredientSynonym;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngredientSynonymRepository extends JpaRepository<IngredientSynonym, Long> {

    @Override
    @EntityGraph(attributePaths = "ingredientMaster")
    List<IngredientSynonym> findAll();
}
