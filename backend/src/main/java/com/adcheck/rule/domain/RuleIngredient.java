package com.adcheck.rule.domain;

import com.adcheck.product.domain.IngredientMaster;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(
        name = "rule_ingredients",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rule_ingredients_rule_ingredient",
                columnNames = {"rule_id", "ingredient_master_id"}
        )
)
public class RuleIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_master_id", nullable = false)
    private IngredientMaster ingredientMaster;

    protected RuleIngredient() {
    }
}
