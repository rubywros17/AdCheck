package com.adcheck.analysis.service;

import com.adcheck.rule.service.RuleAnalysisRequest;
import org.springframework.stereotype.Component;

/**
 * {@link ExtractedClaim}의 {@link Source}만 보고 Rule Engine이 요구하는
 * {@link RuleAnalysisRequest.Context}를 채우는 최소(규칙 기반) 구현 — AI 호출 없음, 순수
 * 결정론적 로직이다.
 *
 * <p>규칙: 상세페이지 본문(DOM_TEXT)이나 이미지 OCR(OCR_IMAGE)에서 나온 문장이면
 * {@code PRODUCT_HEALTH_EFFECT_COPY}로 확정하고, {@code contextEvidence}에 그 출처를
 * 남긴다. 그 외(source가 없거나 알 수 없는 sourceType)는 {@code UNKNOWN}으로 둔다.
 *
 * <p><b>알려진 한계</b>: 이건 "이 문장이 상세페이지/OCR에서 나왔다"만 확인할 뿐, 실제로
 * 그 문장이 제품의 건강 효과를 말하는지(배송·이벤트 안내 등이 아닌지)는 검증하지 않는다.
 * {@code docs/rule-engine.md}가 정의하는 {@code PRODUCT_HEALTH_EFFECT_COPY}의 원래 의미
 * ("주변 자료에서 확인한 경우")보다 느슨한, 의도적으로 단순화한 최소 구현이다 — AI#1이
 * "효능/기능성 주장으로 보이면 일단 포함"하도록 프롬프트되어 있어 이 claims 자체가 이미
 * 어느 정도 걸러진 입력이라는 전제에 기대고 있다.
 */
@Component
public class ClaimContextClassifier {

    public RuleAnalysisRequest.Context classify(ExtractedClaim claim) {
        Source source = claim.source();
        if (source == null || source.sourceType() == null) {
            return RuleAnalysisRequest.Context.UNKNOWN;
        }
        return switch (source.sourceType()) {
            case "DOM_TEXT", "OCR_IMAGE" -> RuleAnalysisRequest.Context.PRODUCT_HEALTH_EFFECT_COPY;
            default -> RuleAnalysisRequest.Context.UNKNOWN;
        };
    }

    public String contextEvidence(ExtractedClaim claim) {
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
