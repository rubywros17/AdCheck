package com.adcheck.reference.repository;

import com.adcheck.reference.domain.ReferenceSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceSourceRepository extends JpaRepository<ReferenceSource, Long> {
}
