package com.adcheck.adcase.repository;

import com.adcheck.adcase.domain.AdCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdCaseRepository extends JpaRepository<AdCase, Long> {
}
