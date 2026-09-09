package com.adcheck.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_report_no", nullable = false, unique = true)
    private String productReportNo;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "primary_function_raw", columnDefinition = "text")
    private String primaryFunctionRaw;

    @Column(name = "functional_ingredient_raw", columnDefinition = "text")
    private String functionalIngredientRaw;

    @Column(name = "product_type")
    private String productType;

    @Column(name = "production_status")
    private String productionStatus;

    @Column(name = "source_updated_at", nullable = false)
    private LocalDate sourceUpdatedAt;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    protected Product() {
    }
}
