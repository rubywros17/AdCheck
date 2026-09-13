package com.adcheck.analysis.repository;

import com.adcheck.analysis.domain.Analysis;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    default Optional<Analysis> findLatestReusableCompleted(
            String normalizedUrl,
            String contentHash,
            String pipelineVersion
    ) {
        return findReusableCompletedCandidates(
                normalizedUrl,
                contentHash,
                pipelineVersion,
                PageRequest.of(0, 1)
        ).stream().findFirst();
    }

    @Query("""
            SELECT analysis
            FROM Analysis analysis
            WHERE analysis.normalizedUrl = :normalizedUrl
              AND analysis.contentHash = :contentHash
              AND analysis.pipelineVersion = :pipelineVersion
              AND analysis.status = com.adcheck.analysis.domain.AnalysisStatus.COMPLETED
            ORDER BY analysis.completedAt DESC, analysis.createdAt DESC, analysis.id DESC
            """)
    List<Analysis> findReusableCompletedCandidates(
            @Param("normalizedUrl") String normalizedUrl,
            @Param("contentHash") String contentHash,
            @Param("pipelineVersion") String pipelineVersion,
            Pageable pageable
    );

    default Optional<Analysis> findActiveByReuseKey(
            String normalizedUrl,
            String contentHash,
            String pipelineVersion
    ) {
        return findActiveCandidates(
                normalizedUrl,
                contentHash,
                pipelineVersion,
                PageRequest.of(0, 1)
        ).stream().findFirst();
    }

    @Query("""
            SELECT analysis
            FROM Analysis analysis
            WHERE analysis.normalizedUrl = :normalizedUrl
              AND analysis.contentHash = :contentHash
              AND analysis.pipelineVersion = :pipelineVersion
              AND analysis.status IN (
                  com.adcheck.analysis.domain.AnalysisStatus.PENDING,
                  com.adcheck.analysis.domain.AnalysisStatus.PROCESSING
              )
            ORDER BY analysis.createdAt DESC, analysis.id DESC
            """)
    List<Analysis> findActiveCandidates(
            @Param("normalizedUrl") String normalizedUrl,
            @Param("contentHash") String contentHash,
            @Param("pipelineVersion") String pipelineVersion,
            Pageable pageable
    );
}
