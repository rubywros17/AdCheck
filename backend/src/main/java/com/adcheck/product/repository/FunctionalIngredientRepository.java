package com.adcheck.product.repository;

import com.adcheck.product.domain.FunctionalIngredient;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FunctionalIngredientRepository extends JpaRepository<FunctionalIngredient, Long> {

    @Override
    @EntityGraph(attributePaths = "ingredientMaster")
    List<FunctionalIngredient> findAll();

    List<FunctionalIngredient> findAllByIngredientMaster_Id(Long ingredientMasterId);

    @EntityGraph(attributePaths = "ingredientMaster")
    List<FunctionalIngredient> findAllByIngredientMaster_IdIn(
            Collection<Long> ingredientMasterIds
    );
}
