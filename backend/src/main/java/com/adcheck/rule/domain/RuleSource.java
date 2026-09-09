package com.adcheck.rule.domain;

import com.adcheck.reference.domain.ReferenceSource;
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
        name = "rule_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rule_sources_rule_source",
                columnNames = {"rule_id", "reference_source_id"}
        )
)
public class RuleSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_source_id", nullable = false)
    private ReferenceSource referenceSource;

    protected RuleSource() {
    }
}
