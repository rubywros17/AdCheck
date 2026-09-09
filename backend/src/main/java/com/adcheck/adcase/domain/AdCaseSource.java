package com.adcheck.adcase.domain;

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
        name = "ad_case_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ad_case_sources_case_source",
                columnNames = {"ad_case_id", "reference_source_id"}
        )
)
public class AdCaseSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ad_case_id", nullable = false)
    private AdCase adCase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_source_id", nullable = false)
    private ReferenceSource referenceSource;

    protected AdCaseSource() {
    }
}
