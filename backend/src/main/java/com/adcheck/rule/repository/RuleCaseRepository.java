package com.adcheck.rule.repository;

import com.adcheck.rule.domain.RuleCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleCaseRepository extends JpaRepository<RuleCase, Long> {
}
