package com.adcheck.product.repository;

import com.adcheck.product.domain.IngredientMaster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngredientMasterRepository extends JpaRepository<IngredientMaster, Long> {
}
