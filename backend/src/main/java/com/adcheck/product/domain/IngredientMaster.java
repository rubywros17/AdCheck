package com.adcheck.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "ingredient_master")
public class IngredientMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "standard_name", nullable = false, unique = true)
    private String standardName;

    @Column(name = "ingredient_code", unique = true)
    private String ingredientCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingredient_category", nullable = false)
    private IngredientCategory ingredientCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "support_status", nullable = false)
    private SupportStatus supportStatus;

    protected IngredientMaster() {
    }
}
