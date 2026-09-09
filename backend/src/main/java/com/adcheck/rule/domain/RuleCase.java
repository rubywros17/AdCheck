package com.adcheck.rule.domain;

import com.adcheck.adcase.domain.AdCase;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(
        name = "rule_cases",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rule_cases_rule_case",
                columnNames = {"rule_id", "ad_case_id"}
        )
)
public class RuleCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ad_case_id", nullable = false)
    private AdCase adCase;

    protected RuleCase() {
    }
}
