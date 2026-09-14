package com.adcheck.rule.repository;

import com.adcheck.rule.domain.Rule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleRepository extends JpaRepository<Rule, Long> {
    List<Rule> findAllByScopeTypeOrderByRuleCodeAsc(String scopeType);
}
