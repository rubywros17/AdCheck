package com.adcheck.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "notified_ingredients")
public class NotifiedIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_master_id")
    private IngredientMaster ingredientMaster;

    @Column(name = "standard_ingredient_name", nullable = false)
    private String standardIngredientName;

    @Column(name = "official_function_raw", columnDefinition = "text")
    private String officialFunctionRaw;

    @Column(name = "daily_intake_raw", nullable = false, columnDefinition = "text")
    private String dailyIntakeRaw;

    @Column(name = "precautions_raw", columnDefinition = "text")
    private String precautionsRaw;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    protected NotifiedIngredient() {
    }
}
