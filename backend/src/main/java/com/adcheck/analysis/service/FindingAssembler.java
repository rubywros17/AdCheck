package com.adcheck.analysis.service;

import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.RiskLevel;
import com.adcheck.product.domain.MatchMethod;
import com.adcheck.product.domain.MatchStatus;
import com.adcheck.product.domain.Product;
import com.adcheck.product.service.OfficialFunctionQueryService;
import com.adcheck.product.service.OfficialFunctionReadModel;
import com.adcheck.product.service.ProductIdentificationCandidate;
import com.adcheck.product.service.ProductIdentificationResult;
import com.adcheck.product.service.ProductIdentificationService;
import com.adcheck.product.service.ProductIngredientQueryService;
import com.adcheck.product.service.ProductIngredientReadModel;
import com.adcheck.rule.model.RiskSignalContext;
import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.service.RuleAnalysisRequest;
import com.adcheck.rule.service.RuleAnalysisResult;
import com.adcheck.rule.service.RuleAnalysisService;
import com.adcheck.rule.service.RuleEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 이식된 부품(AI#1, IngredientMatchingService, Product/Ingredient 조회, Rule Engine, RAG,
 * AI#2)을 실제로 연결해서 하나의 {@link ExtractedClaim}당 하나의 {@link Finding}(또는 0개)을
 * 만드는 조립 어댑터. 이 클래스가 Backend 내부 파이프라인의 실질적인 오케스트레이터다.
 *
 * <p>전체 흐름:
 * <ol>
 *   <li>{@code productCandidates} 중 confidence가 가장 높은 것으로 {@link ProductIdentificationService}
 *       호출 — 확정 실패(NOT_FOUND/AMBIGUOUS)해도 실패로 취급하지 않고 product=null로 계속 진행한다
 *       (C07/C24처럼 원료·제품 확정 없이도 평가 가능한 규칙이 있기 때문).</li>
 *   <li>확정된 Product가 있으면 {@link ProductIngredientQueryService}로 사전계산된 원료를 조회하고,
 *       {@code ingredientCandidates} 중 사전계산에 없는 rawText만 {@link IngredientMatchingService}로
 *       실시간 매칭해서 보충한다.</li>
 *   <li>확정된 ingredientMasterId로 {@link OfficialFunctionQueryService} 조회 후
 *       {@link ConfirmedIngredientAssembler}(변경 없이 재사용)로 AI#2 계약 형태로 조립한다.</li>
 *   <li>Claim마다 {@link ClaimContextClassifier}로 context를 채우고 {@link RuleAnalysisService}를
 *       호출한다. MATCHED 규칙이 있으면 AI#2까지, REVIEW_REQUIRED만 있으면 고정 메시지로,
 *       전부 NOT_MATCHED면 Finding 없이 넘어간다.</li>
 * </ol>
 *
 * <p><b>예외 처리 정책</b>: Product/Ingredient/OfficialFunction 조회처럼 전체 Claim에 공통으로
 * 쓰이는 단계에서 예외가 나면 그대로 위로 던져(catch하지 않음) {@code AnalysisBackgroundJob}의
 * 기존 최상위 정책(전체 FAILED)을 그대로 탄다 — DB 연결 등 인프라 문제는 개별 Claim 문제가
 * 아니라 이 분석 전체를 신뢰할 수 없게 만들기 때문이다. 반면 Claim 하나에 대한 Rule 판정/RAG
 * 검색/AI#2 비교 중 예외가 나면 그 Claim만 로그를 남기고 건너뛰며, 나머지 Claim은 계속
 * 처리한다 — 이미 성공한 AI#1 추출 결과를 다른 Claim의 일시적 실패(네트워크 타임아웃 등) 때문에
 * 통째로 버리지 않기 위함이다.
 */
@Service
public class FindingAssembler {

    private static final Logger log = LoggerFactory.getLogger(FindingAssembler.class);
    private static final int RAG_TOP_K = 3;

    private final ProductIdentificationService productIdentificationService;
    private final ProductIngredientQueryService productIngredientQueryService;
    private final OfficialFunctionQueryService officialFunctionQueryService;
    private final IngredientMatchingService ingredientMatchingService;
    private final ConfirmedIngredientAssembler confirmedIngredientAssembler;
    private final ClaimContextClassifier contextClassifier;
    private final RuleAnalysisService ruleAnalysisService;
    private final RagRetrievalService ragRetrievalService;
    private final GeminiClaimComparisonService geminiClaimComparisonService;

    public FindingAssembler(
            ProductIdentificationService productIdentificationService,
            ProductIngredientQueryService productIngredientQueryService,
            OfficialFunctionQueryService officialFunctionQueryService,
            IngredientMatchingService ingredientMatchingService,
            ConfirmedIngredientAssembler confirmedIngredientAssembler,
            ClaimContextClassifier contextClassifier,
            RuleAnalysisService ruleAnalysisService,
            RagRetrievalService ragRetrievalService,
            GeminiClaimComparisonService geminiClaimComparisonService
    ) {
        this.productIdentificationService = productIdentificationService;
        this.productIngredientQueryService = productIngredientQueryService;
        this.officialFunctionQueryService = officialFunctionQueryService;
        this.ingredientMatchingService = ingredientMatchingService;
        this.confirmedIngredientAssembler = confirmedIngredientAssembler;
        this.contextClassifier = contextClassifier;
        this.ruleAnalysisService = ruleAnalysisService;
        this.ragRetrievalService = ragRetrievalService;
        this.geminiClaimComparisonService = geminiClaimComparisonService;
    }

    public Result assemble(ClaimAnalysisResult claimResult) {
        Product product = identifyProduct(claimResult.productCandidates());

        List<ProductIngredientReadModel> precomputed = product != null
                ? productIngredientQueryService.findByProduct(product).ingredients()
                : List.of();
        List<ProductIngredientReadModel> confirmedIngredients =
                resolveIngredients(precomputed, claimResult.ingredientCandidates());

        List<Long> ingredientMasterIds = confirmedIngredients.stream()
                .map(ProductIngredientReadModel::ingredientMasterId)
                .distinct()
                .toList();
        List<OfficialFunctionReadModel> officialFunctionModels = ingredientMasterIds.isEmpty()
                ? List.of()
                : officialFunctionQueryService.findAllByIngredientMasterIds(ingredientMasterIds);

        ConfirmedIngredientAssembler.Assembled assembled =
                confirmedIngredientAssembler.assemble(confirmedIngredients, officialFunctionModels);

        ConfirmedProduct confirmedProduct = product != null
                ? new ConfirmedProduct(product.getProductReportNo(), product.getProductName())
                : new ConfirmedProduct(null, null);

        Set<Long> confirmedIngredientMasterIds = Set.copyOf(ingredientMasterIds);
        RuleAnalysisRequest.OfficialFunctions officialFunctionsContext = new RuleAnalysisRequest.OfficialFunctions(
                officialFunctionModels.stream().map(this::toRuleOfficialFunctionContext).toList(),
                false,
                false
        );

        List<ClaimRuleOutcome> outcomes = new ArrayList<>();
        for (ExtractedClaim claim : claimResult.claims()) {
            try {
                outcomes.add(evaluateClaim(
                        claim, claimResult.riskSignalCandidates(), confirmedIngredientMasterIds, officialFunctionsContext
                ));
            } catch (RuntimeException e) {
                log.warn("Claim {} 의 Rule 판정 중 오류가 발생해 이 Claim은 건너뜁니다: {}",
                        claim.claimId(), e.getMessage(), e);
            }
        }

        List<ClaimRuleOutcome> needsAi2 = outcomes.stream().filter(o -> !o.matched().isEmpty()).toList();
        Map<String, List<Evidence>> evidenceByClaimId = searchEvidence(needsAi2);

        List<Finding> findings = new ArrayList<>();
        for (ClaimRuleOutcome outcome : outcomes) {
            if (!outcome.matched().isEmpty()) {
                try {
                    findings.addAll(compareWithAi2(
                            outcome, confirmedProduct, assembled,
                            evidenceByClaimId.getOrDefault(outcome.claim().claimId(), List.of())
                    ));
                } catch (RuntimeException e) {
                    log.warn("Claim {} 의 AI#2 비교 중 오류가 발생해 이 Claim은 건너뜁니다: {}",
                            outcome.claim().claimId(), e.getMessage(), e);
                }
            } else if (!outcome.reviewRequired().isEmpty()) {
                findings.add(toReviewRequiredFinding(outcome));
            }
            // matched/reviewRequired 둘 다 비어있으면(전부 NOT_MATCHED) Finding을 만들지 않는다.
        }

        return new Result(product, List.copyOf(findings), assembled.officialFunctions().size());
    }

    private Product identifyProduct(List<ProductCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(c -> c.confidence() != null ? c.confidence() : -1.0))
                .map(c -> productIdentificationService.identify(
                        new ProductIdentificationCandidate(c.productReportNo(), c.productName(), c.companyName())
                ))
                .filter(result -> result.status() == ProductIdentificationResult.Status.FOUND)
                .map(ProductIdentificationResult::product)
                .orElse(null);
    }

    /**
     * 사전계산된 {@code ProductIngredientQueryService} 결과에 없는 rawText만
     * {@link IngredientMatchingService}로 실시간 매칭해서 보충한다. 이미 확정된
     * ingredientMasterId와 겹치면 중복 추가하지 않는다.
     */
    private List<ProductIngredientReadModel> resolveIngredients(
            List<ProductIngredientReadModel> precomputed,
            List<IngredientCandidate> candidates
    ) {
        Set<String> coveredRawTexts = new LinkedHashSet<>();
        Set<Long> seenMasterIds = new LinkedHashSet<>();
        for (ProductIngredientReadModel model : precomputed) {
            coveredRawTexts.add(model.rawText());
            seenMasterIds.add(model.ingredientMasterId());
        }

        List<ProductIngredientReadModel> merged = new ArrayList<>(precomputed);
        for (IngredientCandidate candidate : candidates) {
            String rawText = candidate.rawText();
            if (rawText == null || rawText.isBlank() || coveredRawTexts.contains(rawText)) {
                continue;
            }
            IngredientMatchResult matchResult = ingredientMatchingService.match(rawText);
            if (!IngredientMatchingService.MATCHED.equals(matchResult.matchStatus())) {
                continue;
            }
            if (!seenMasterIds.add(matchResult.ingredientMasterId())) {
                continue;
            }
            merged.add(new ProductIngredientReadModel(
                    matchResult.ingredientMasterId(),
                    matchResult.standardName(),
                    matchResult.rawText(),
                    MatchMethod.valueOf(matchResult.matchMethod()),
                    MatchStatus.valueOf(matchResult.matchStatus())
            ));
        }
        return merged;
    }

    private RuleOfficialFunctionContext toRuleOfficialFunctionContext(OfficialFunctionReadModel model) {
        return new RuleOfficialFunctionContext(
                model.ingredientMasterId(),
                model.canonicalName(),
                model.officialFunctionText(),
                model.sourceType() == null ? null : model.sourceType().name(),
                model.recognitionNo()
        );
    }

    private ClaimRuleOutcome evaluateClaim(
            ExtractedClaim claim,
            List<RiskSignalCandidate> allRiskSignals,
            Set<Long> confirmedIngredientMasterIds,
            RuleAnalysisRequest.OfficialFunctions officialFunctionsContext
    ) {
        RuleAnalysisRequest.Context context = contextClassifier.classify(claim);
        String contextEvidence = contextClassifier.contextEvidence(claim);
        RuleAnalysisRequest.Claim ruleClaim =
                new RuleAnalysisRequest.Claim(claim.claimId(), claim.claimText(), context, contextEvidence);

        List<RiskSignalContext> riskSignals = allRiskSignals.stream()
                .filter(rs -> claim.claimId().equals(rs.claimId()))
                .filter(rs -> rs.signalType() != null && !rs.signalType().isBlank())
                .map(rs -> new RiskSignalContext(rs.signalType(), rs.text()))
                .toList();

        RuleAnalysisRequest request = new RuleAnalysisRequest(
                ruleClaim, riskSignals, confirmedIngredientMasterIds, officialFunctionsContext
        );
        RuleAnalysisResult result = ruleAnalysisService.analyze(request);

        List<RuleAnalysisResult.RuleMatch> matched = result.matches().stream()
                .filter(m -> m.evaluation().status() == RuleEvaluation.Status.MATCHED)
                .toList();
        List<RuleAnalysisResult.RuleMatch> reviewRequired = result.matches().stream()
                .filter(m -> m.evaluation().status() == RuleEvaluation.Status.REVIEW_REQUIRED)
                .toList();

        return new ClaimRuleOutcome(claim, matched, reviewRequired);
    }

    private Map<String, List<Evidence>> searchEvidence(List<ClaimRuleOutcome> needsAi2) {
        if (needsAi2.isEmpty()) {
            return Map.of();
        }
        List<RagRetrievalService.ClaimQuery> queries = needsAi2.stream()
                .map(outcome -> new RagRetrievalService.ClaimQuery(
                        outcome.claim().claimId(),
                        outcome.claim().claimText(),
                        outcome.matched().stream()
                                .flatMap(m -> m.sources().stream())
                                .map(RuleAnalysisResult.SourceMetadata::sourceId)
                                .distinct()
                                .toList()
                ))
                .toList();
        try {
            return ragRetrievalService.searchBatch(queries, RAG_TOP_K);
        } catch (RuntimeException e) {
            log.warn("RAG 근거 문단 검색 실패, 근거 없이 진행합니다: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    private List<Finding> compareWithAi2(
            ClaimRuleOutcome outcome,
            ConfirmedProduct confirmedProduct,
            ConfirmedIngredientAssembler.Assembled assembled,
            List<Evidence> evidence
    ) {
        List<RuleMatch> slimMatches = outcome.matched().stream().map(this::toSlimRuleMatch).toList();

        ClaimComparisonRequest request = new ClaimComparisonRequest(
                List.of(new ComparisonClaim(outcome.claim().claimId(), outcome.claim().claimText())),
                confirmedProduct,
                assembled.confirmedIngredients(),
                assembled.officialFunctions(),
                slimMatches,
                evidence
        );
        ClaimComparisonResult result = geminiClaimComparisonService.compare(request);

        RiskLevel riskLevel = mostSevere(outcome.matched())
                .map(m -> RiskLevel.fromSeverity(m.severity()))
                .orElse(RiskLevel.CAUTION);
        String category = mostSevere(outcome.matched())
                .map(RuleAnalysisResult.RuleMatch::judgmentCategory)
                .orElse("UNKNOWN");

        return result.claimComparisons().stream()
                .map(comparison -> new Finding(
                        outcome.claim().claimText(),
                        outcome.claim().source() != null ? outcome.claim().source().selector() : null,
                        riskLevel,
                        category,
                        (comparison.explanation() != null && !comparison.explanation().isBlank())
                                ? comparison.explanation() : comparison.reason(),
                        comparison.officialFunction()
                ))
                .toList();
    }

    private Finding toReviewRequiredFinding(ClaimRuleOutcome outcome) {
        RiskLevel riskLevel = mostSevere(outcome.reviewRequired())
                .map(m -> RiskLevel.fromSeverity(m.severity()))
                .orElse(RiskLevel.CAUTION);
        String category = mostSevere(outcome.reviewRequired())
                .map(RuleAnalysisResult.RuleMatch::judgmentCategory)
                .orElse("UNKNOWN");

        return new Finding(
                outcome.claim().claimText(),
                outcome.claim().source() != null ? outcome.claim().source().selector() : null,
                riskLevel,
                category,
                "확인이 필요한 표현입니다.",
                null
        );
    }

    private RuleMatch toSlimRuleMatch(RuleAnalysisResult.RuleMatch match) {
        return new RuleMatch(
                match.claimId(), match.ruleCode(), match.severity(), match.judgmentCategory(), match.evaluation().reason()
        );
    }

    /**
     * 대표로 고를 RuleMatch 하나를 정한다. 우선순위는 두 단계: ①{@code UNSUPPORTED_RULE}
     * (evaluator가 아예 없어 판정 시도조차 안 된 것)이 아닌, 실제로 판정을 시도한 결과를
     * 먼저 우선하고, ②그 안에서만 심각도(HIGH가 CAUTION/NORMAL보다 우선)로 고른다 — 그래야
     * severity가 우연히 더 높다는 이유로 "안 본 것"이 "봤는데 애매한 것"을 밀어내지 않는다.
     */
    /* package-private for direct unit test coverage (FindingAssemblerMostSevereTest) without DB. */
    Optional<RuleAnalysisResult.RuleMatch> mostSevere(List<RuleAnalysisResult.RuleMatch> matches) {
        return matches.stream()
                .min(Comparator
                        .comparing((RuleAnalysisResult.RuleMatch m) ->
                                m.evaluation().reasonCode() == RuleEvaluation.ReasonCode.UNSUPPORTED_RULE)
                        .thenComparingInt(m -> RiskLevel.fromSeverity(m.severity()).ordinal()));
    }

    private record ClaimRuleOutcome(
            ExtractedClaim claim,
            List<RuleAnalysisResult.RuleMatch> matched,
            List<RuleAnalysisResult.RuleMatch> reviewRequired
    ) {
    }

    public record Result(Product product, List<Finding> findings, int officialFunctionMatchedCount) {
    }
}
