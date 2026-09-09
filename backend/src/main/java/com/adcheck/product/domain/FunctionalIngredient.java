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

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "functional_ingredients")
public class FunctionalIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_master_id")
    private IngredientMaster ingredientMaster;

    @Column(name = "recognition_no")
    private String recognitionNo;

    @Column(name = "ingredient_name")
    private String ingredientName;

    @Column(name = "recognized_at", nullable = false)
    private LocalDate recognizedAt;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "official_function_raw", columnDefinition = "text")
    private String officialFunctionRaw;

    @Column(name = "official_function_normalized", columnDefinition = "text")
    private String officialFunctionNormalized;

    @Column(name = "daily_intake_raw", columnDefinition = "text")
    private String dailyIntakeRaw;

    @Column(name = "daily_intake_normalized", columnDefinition = "text")
    private String dailyIntakeNormalized;

    @Column(name = "precautions_raw", columnDefinition = "text")
    private String precautionsRaw;

    @Column(name = "precautions_normalized", columnDefinition = "text")
    private String precautionsNormalized;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    protected FunctionalIngredient() {
    }
}
