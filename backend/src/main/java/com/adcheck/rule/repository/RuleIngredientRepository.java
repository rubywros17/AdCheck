package com.adcheck.rule.repository;

import com.adcheck.rule.domain.RuleIngredient;
import com.adcheck.rule.domain.Rule;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuleIngredientRepository extends JpaRepository<RuleIngredient, Long> {
    @Query("select distinct ri.rule from RuleIngredient ri where ri.ingredientMaster.id in :ids "
            + "and ri.rule.scopeType = 'INGREDIENT_SPECIFIC' order by ri.rule.ruleCode")
    List<Rule> findIngredientSpecificRules(@Param("ids") Collection<Long> ids);
}
