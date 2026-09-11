package com.adcheck.product.repository;

import com.adcheck.product.domain.NotifiedIngredient;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NotifiedIngredientRepository extends JpaRepository<NotifiedIngredient, Long> {

    List<NotifiedIngredient> findAllByIngredientMaster_Id(Long ingredientMasterId);

    @EntityGraph(attributePaths = "ingredientMaster")
    List<NotifiedIngredient> findAllByIngredientMaster_IdIn(
            Collection<Long> ingredientMasterIds
    );
}
