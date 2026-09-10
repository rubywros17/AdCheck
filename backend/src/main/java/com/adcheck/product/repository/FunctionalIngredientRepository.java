package com.adcheck.product.repository;

import com.adcheck.product.domain.FunctionalIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FunctionalIngredientRepository extends JpaRepository<FunctionalIngredient, Long> {

    List<FunctionalIngredient> findAllByIngredientMaster_Id(Long ingredientMasterId);
}
