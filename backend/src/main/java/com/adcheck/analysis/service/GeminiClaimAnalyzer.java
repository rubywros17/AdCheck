package com.adcheck.analysis.service;

import com.adcheck.analysis.config.GeminiApiKeyPresentCondition;
import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ClaimAnalyzer}의 실제 구현체 — Backend의 {@code MockClaimAnalyzer} 자리에 꽂혀서
 * AI 1차 추출(Extraction)만 수행한다. 예전 {@code AnalysisPipelineService}가 하던
 * 오케스트레이션(정제 → OCR → 통합 추출)을 그대로 흡수했다.
 *
 * <p>Product/Ingredient 확정, Rule 판정, 최종 Finding 조립은 Backend 책임이라 이 클래스는
 * 관여하지 않는다 — riskLevel/message/officialFunction을 고정값으로 채운 Finding을 AI가
 * 직접 만들던 이전 방식은 Backend Contract(2026-09-10)로 더 이상 쓰지 않기로 확정됐다.
 *
 * <p>{@code gemini.api-key}가 설정되어 있을 때만 등록된다 — 없으면
 * {@link ClaimAnalyzerConfiguration}의 {@code MockClaimAnalyzer} fallback이 대신 뜬다.
 */
@Service
@Conditional(GeminiApiKeyPresentCondition.class)
public class GeminiClaimAnalyzer implements ClaimAnalyzer {

    private final DetailTextCleaner textCleaner;
    private final GeminiOcrService ocrService;
    private final ProductContentExtractionService extractionService;

    public GeminiClaimAnalyzer(
            DetailTextCleaner textCleaner,
            GeminiOcrService ocrService,
            ProductContentExtractionService extractionService
    ) {
        this.textCleaner = textCleaner;
        this.ocrService = ocrService;
        this.extractionService = extractionService;
    }

    @Override
    public ClaimAnalysisResult analyze(List<PageTextEvidence> texts, List<PageImageEvidence> images) {
        // 블록 단위로 정제한다(합친 뒤 한 번에 정제하지 않는다) — 각 블록의 selector를
        // 끝까지 들고 가야 나중에 Finding.selector를 채울 수 있다(합쳐버리면 유실된다).
        List<PageTextEvidence> cleanedBlocks = texts.stream()
                .map(text -> new PageTextEvidence(textCleaner.clean(text.content()), text.selector()))
                .filter(text -> text.content() != null && !text.content().isBlank())
                .toList();

        List<String> imageUrls = images.stream().map(PageImageEvidence::url).toList();
        Map<String, String> ocrResults = new LinkedHashMap<>();
        ocrService.extractTexts(imageUrls).forEach((url, text) -> {
            if (!text.isBlank()) {
                ocrResults.put(url, text);
            }
        });

        ProductContentExtractionService.ExtractionResult extracted =
                extractionService.extract(cleanedBlocks, ocrResults);

        return new ClaimAnalysisResult(
                extracted.claims(),
                extracted.productCandidates(),
                extracted.ingredientCandidates(),
                extracted.riskSignalCandidates()
        );
    }
}
