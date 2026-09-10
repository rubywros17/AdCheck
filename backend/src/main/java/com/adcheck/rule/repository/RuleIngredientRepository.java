package com.adcheck.rule.repository;

import com.adcheck.rule.domain.RuleIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleIngredientRepository extends JpaRepository<RuleIngredient, Long> {
}
