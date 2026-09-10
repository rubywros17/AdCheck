package com.adcheck.analysis.repository;

import com.adcheck.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByProductReportNo(String productReportNo);
}
