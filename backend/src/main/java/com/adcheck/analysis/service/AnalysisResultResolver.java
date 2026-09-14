package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisProperties;
import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.repository.AnalysisRepository;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultJsonException;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AnalysisResultResolver {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultResolver.class);

    private final AnalysisUrlNormalizer urlNormalizer;
    private final AnalysisRequestFingerprint requestFingerprint;
    private final AnalysisProperties properties;
    private final AnalysisRepository analysisRepository;
    private final AnalysisResultJsonCodec resultJsonCodec;
    private final Clock clock;

    public AnalysisResultResolver(
            AnalysisUrlNormalizer urlNormalizer,
            AnalysisRequestFingerprint requestFingerprint,
            AnalysisProperties properties,
            AnalysisRepository analysisRepository,
            AnalysisResultJsonCodec resultJsonCodec,
            Clock clock
    ) {
        this.urlNormalizer = urlNormalizer;
        this.requestFingerprint = requestFingerprint;
        this.properties = properties;
        this.analysisRepository = analysisRepository;
        this.resultJsonCodec = resultJsonCodec;
        this.clock = clock;
    }

    public AnalysisResultResolution resolve(CreateAnalysisRequest request) {
        AnalysisReuseKey reuseKey = new AnalysisReuseKey(
                urlNormalizer.normalize(request.pageUrl()),
                requestFingerprint.generate(request),
                requirePipelineVersion()
        );

        Optional<Analysis> completed = analysisRepository.findLatestReusableCompleted(
                reuseKey.normalizedUrl(),
                reuseKey.contentHash(),
                reuseKey.pipelineVersion()
        );
        if (completed.isPresent()) {
            Optional<AnalysisResultSnapshot> snapshot = restoreFreshResult(completed.get());
            if (snapshot.isPresent()) {
                return new AnalysisResultResolution.Reused(
                        completed.get().getId(),
                        snapshot.get(),
                        reuseKey
                );
            }
        }

        return analysisRepository.findActiveByReuseKey(
                        reuseKey.normalizedUrl(),
                        reuseKey.contentHash(),
                        reuseKey.pipelineVersion()
                )
                .<AnalysisResultResolution>map(analysis -> new AnalysisResultResolution.InProgress(
                        analysis.getId(),
                        analysis.getStatus(),
                        reuseKey
                ))
                .orElseGet(() -> new AnalysisResultResolution.NewAnalysis(reuseKey));
    }

    private Optional<AnalysisResultSnapshot> restoreFreshResult(Analysis analysis) {
        if (!isFresh(analysis)) {
            return Optional.empty();
        }
        if (analysis.getResultJson() == null || analysis.getResultJson().isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(resultJsonCodec.deserialize(analysis.getResultJson()));
        } catch (AnalysisResultJsonException exception) {
            log.warn(
                    "Stored analysis result could not be restored. analysisId={}",
                    analysis.getId(),
                    exception
            );
            return Optional.empty();
        }
    }

    private boolean isFresh(Analysis analysis) {
        Instant completedAt = analysis.getCompletedAt();
        if (completedAt == null) {
            return false;
        }

        boolean hasFinding = analysis.isHasFinding();
        Duration reuseTtl = hasFinding
                ? properties.getReuseTtlWithFinding()
                : properties.getReuseTtlClean();
        if (reuseTtl == null || reuseTtl.isNegative()) {
            String propertyName = hasFinding
                    ? "adcheck.analysis.reuse-ttl-with-finding"
                    : "adcheck.analysis.reuse-ttl-clean";
            throw new IllegalStateException(propertyName + "은 0 이상이어야 합니다.");
        }

        Instant freshnessBoundary = clock.instant().minus(reuseTtl);
        return !completedAt.isBefore(freshnessBoundary);
    }

    private String requirePipelineVersion() {
        String pipelineVersion = properties.getPipelineVersion();
        if (pipelineVersion == null || pipelineVersion.isBlank()) {
            throw new IllegalStateException("adcheck.analysis.pipeline-version 설정이 필요합니다.");
        }
        return pipelineVersion;
    }
}
