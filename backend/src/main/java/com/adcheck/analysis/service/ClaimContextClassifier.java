package com.adcheck.analysis.service;

import com.adcheck.rule.service.RuleAnalysisRequest;
import org.springframework.stereotype.Component;

/**
 * {@link ExtractedClaim}에서 Rule Engine이 요구하는 {@link RuleAnalysisRequest.Context}를
 * 채운다 — 이 클래스 자체는 여전히 AI를 직접 호출하지 않는다(2026-09-16 기준).
 *
 * <p><b>우선순위(팀 합의, 2026-09-16)</b>: {@code ProductContentExtractionService}(AI#1)가
 * 이미 페이지 전체 문맥을 보고 판단해 {@code claim.context()}/{@code claim.contextEvidence()}에
 * 채워둔 값이 있으면(즉 {@code UNKNOWN}이 아니면) 그 값을 그대로 쓴다. AI#1이 확신하지
 * 못해 {@code UNKNOWN}을 준 경우에만, {@link Source}만 보는 아래의 기존 규칙 기반 로직으로
 * 폴백한다: 상세페이지 본문(DOM_TEXT)이나 이미지 OCR(OCR_IMAGE)에서 나온 문장이면
 * {@code PRODUCT_HEALTH_EFFECT_COPY}로 확정하고 {@code contextEvidence}에 그 출처를 남기며,
 * 그 외(source가 없거나 알 수 없는 sourceType)는 {@code UNKNOWN}으로 둔다.
 *
 * <p><b>폴백 로직의 알려진 한계</b>: 이건 "이 문장이 상세페이지/OCR에서 나왔다"만 확인할
 * 뿐, 실제로 그 문장이 제품의 건강 효과를 말하는지(리뷰·인용문·무관 정보가 아닌지)는
 * 검증하지 않는다 — 그래서 AI#1이 판단한 값이 있으면 그걸 우선하는 것이다. AI#1도
 * 확신하지 못하는 극히 일부 케이스에서만 이 느슨한 폴백이 실제로 쓰인다.
 */
@Component
public class ClaimContextClassifier {

    public RuleAnalysisRequest.Context classify(ExtractedClaim claim) {
        if (claim.context() != RuleAnalysisRequest.Context.UNKNOWN) {
            return claim.context();
        }
        return classifyBySource(claim);
    }

    public String contextEvidence(ExtractedClaim claim) {
        if (claim.context() != RuleAnalysisRequest.Context.UNKNOWN) {
            return claim.contextEvidence();
        }
        return contextEvidenceBySource(claim);
    }

    private RuleAnalysisRequest.Context classifyBySource(ExtractedClaim claim) {
        Source source = claim.source();
        if (source == null || source.sourceType() == null) {
            return RuleAnalysisRequest.Context.UNKNOWN;
        }
        return switch (source.sourceType()) {
            case "DOM_TEXT", "OCR_IMAGE" -> RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
            default -> RuleAnalysisRequest.Context.UNKNOWN;
        };
    }

    private String contextEvidenceBySource(ExtractedClaim claim) {
        Source source = claim.source();
        if (source == null || source.sourceType() == null) {
            return null;
        }
        return switch (source.sourceType()) {
            case "DOM_TEXT" -> "DOM_TEXT: " + (source.selector() != null ? source.selector() : "(selector 없음)");
            case "OCR_IMAGE" -> "OCR_IMAGE: " + source.imageUrl();
            default -> null;
        };
    }
}
