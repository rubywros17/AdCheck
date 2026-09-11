package com.adcheck.rule.repository;

import com.adcheck.rule.domain.RuleSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuleSourceRepository extends JpaRepository<RuleSource, Long> {
    @Query("select rs from RuleSource rs join fetch rs.rule join fetch rs.referenceSource "
            + "where rs.rule.id in :ids order by rs.rule.ruleCode, rs.referenceSource.sourceId")
    List<RuleSource> findAllWithSourceByRuleIds(@Param("ids") Collection<Long> ids);
}
