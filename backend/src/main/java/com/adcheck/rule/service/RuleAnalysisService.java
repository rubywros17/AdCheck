package com.adcheck.rule.service;

import com.adcheck.rule.config.RuleJudgeProperties;
import com.adcheck.rule.domain.Rule;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.adcheck.rule.service.RuleAnalysisResult.Diagnostic;
import com.adcheck.rule.service.RuleAnalysisResult.RuleMatch;

@Service
public class RuleAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(RuleAnalysisService.class);

    private final RuleSelector selector;
    private final RuleEvaluatorRegistry registry;
    private final RuleSourceResolver sourceResolver;
    private final ExecutorService ruleJudgeExecutor;

    /** 테스트 편의용 — 동시 실행 없이(단일 스레드) 순서대로 평가한다. */
    public RuleAnalysisService(RuleSelector selector, RuleEvaluatorRegistry registry, RuleSourceResolver sourceResolver) {
        this(selector, registry, sourceResolver, 1);
    }

    @Autowired
    public RuleAnalysisService(RuleSelector selector, RuleEvaluatorRegistry registry,
                               RuleSourceResolver sourceResolver, RuleJudgeProperties properties) {
        this(selector, registry, sourceResolver, properties == null ? 1 : properties.getConcurrency());
    }

    private RuleAnalysisService(RuleSelector selector, RuleEvaluatorRegistry registry,
                                RuleSourceResolver sourceResolver, int concurrency) {
        this.selector = selector;
        this.registry = registry;
        this.sourceResolver = sourceResolver;
        this.ruleJudgeExecutor = Executors.newFixedThreadPool(Math.max(1, concurrency), runnable -> {
            Thread thread = new Thread(runnable, "rule-judge-");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        ruleJudgeExecutor.shutdown();
    }

    private record EvaluatedRule(Rule rule, RuleEvaluation evaluation) { }

    /**
     * 여러 Claim을 한 번에 분석한다 — Claim마다 {@link #analyze}를 부르는 대신 <b>규칙 축으로
     * 뒤집어서</b>, 규칙 하나당 모든 Claim을 한 번에 평가한다. AI 평가기가 이걸 한 번의 호출로
     * 묶기 때문에(@link RuleEvaluator#evaluateAcrossClaims) 호출 수가 "Claim수 × 규칙수"에서
     * "규칙수"로 떨어진다 — 무료 티어 분당 한도(15회) 안에서 완주하려면 사실상 필수다.
     *
     * <p>규칙 하나의 평가가 끝내 실패해도(재시도 후에도 429 등) 그 규칙만 REVIEW_REQUIRED로
     * 표시하고 나머지는 계속 진행한다. 예전처럼 Claim을 통째로 버리면 "검사했는데 문제 없음"과
     * "검사하다 실패함"이 구분되지 않아 사용자가 잘못된 안심을 하게 된다.
     *
     * <p>{@code @Transactional}을 걸지 않는다 — AI 호출이 수 초씩 걸리는 동안 DB 커넥션을 붙잡고
     * 있을 이유가 없고, 여기서 필요한 DB 조회(규칙 선택·출처 결합)는 각자 트랜잭션으로 충분하다.
     */
    public List<RuleAnalysisResult> analyzeAll(List<RuleAnalysisRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        if (requests.isEmpty()) {
            return List.of();
        }
        // 확정 원료는 상품 단위라 Claim마다 같다 — 규칙 선택은 한 번이면 된다.
        var rules = selector.select(requests.get(0).confirmedIngredientMasterIds());

        // 규칙끼리는 서로 독립적이라 동시에 평가한다 — 규칙 하나당 호출 1회이므로 동시 호출 수는
        // concurrency 설정값을 넘지 않는다(무료 티어 분당 한도를 한꺼번에 소진하지 않기 위함).
        Map<Long, CompletableFuture<List<RuleEvaluation>>> pending = new LinkedHashMap<>();
        for (Rule rule : rules) {
            pending.put(rule.getId(),
                    CompletableFuture.supplyAsync(() -> evaluateRuleSafely(rule, requests), ruleJudgeExecutor));
        }
        Map<Long, List<RuleEvaluation>> evaluationsByRuleId = new LinkedHashMap<>();
        pending.forEach((ruleId, future) -> evaluationsByRuleId.put(ruleId, future.join()));

        List<Long> sourceRuleIds = rules.stream()
                .filter(rule -> evaluationsByRuleId.get(rule.getId()).stream()
                        .anyMatch(evaluation -> evaluation.status() != RuleEvaluation.Status.NOT_MATCHED))
                .map(Rule::getId)
                .toList();
        var sources = sourceResolver.resolve(sourceRuleIds);

        List<RuleAnalysisResult> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            RuleAnalysisRequest request = requests.get(i);
            int claimIndex = i;
            List<RuleMatch> matches = rules.stream()
                    .map(rule -> toRuleMatch(rule, request,
                            evaluationsByRuleId.get(rule.getId()).get(claimIndex), sources))
                    .toList();
            results.add(new RuleAnalysisResult(request.claim().id(), matches,
                    request.confirmedIngredientMasterIds().isEmpty()
                            ? List.of(Diagnostic.INGREDIENT_SPECIFIC_NOT_EVALUATED) : List.of(),
                    request.riskSignals()));
        }
        return results;
    }

    private List<RuleEvaluation> evaluateRuleSafely(Rule rule, List<RuleAnalysisRequest> requests) {
        try {
            List<RuleEvaluation> evaluations = registry.evaluateAcrossClaims(rule, requests);
            if (evaluations.size() == requests.size()) {
                return evaluations;
            }
            log.warn("규칙 {} 평가 결과 개수 불일치 (기대 {}건, 실제 {}건) — 확인 필요로 처리합니다",
                    rule.getRuleCode(), requests.size(), evaluations.size());
        } catch (RuntimeException e) {
            log.warn("규칙 {} 평가에 실패해 이 규칙만 확인 필요로 처리합니다: {}", rule.getRuleCode(), e.getMessage());
        }
        return Collections.nCopies(requests.size(), new RuleEvaluation(
                RuleEvaluation.Status.REVIEW_REQUIRED, RuleEvaluation.ReasonCode.SEMANTIC_COMPARISON_REQUIRED,
                "자동 판정에 실패해 확인이 필요합니다."));
    }

    private RuleMatch toRuleMatch(Rule rule, RuleAnalysisRequest request, RuleEvaluation evaluation,
                                  Map<Long, List<RuleAnalysisResult.SourceMetadata>> sources) {
        var resolved = sources.getOrDefault(rule.getId(), List.of());
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (evaluation.status() != RuleEvaluation.Status.NOT_MATCHED && resolved.isEmpty()) {
            diagnostics.add(Diagnostic.SOURCE_MISSING);
        }
        if ("DRAFT".equals(rule.getReviewStatus())) diagnostics.add(Diagnostic.DRAFT_RULE);
        return new RuleMatch(request.claim().id(), rule.getId(), rule.getRuleCode(), rule.getRuleVersion(),
                rule.getScopeType(), rule.getJudgmentCategory(), rule.getSeverity(), rule.getReviewStatus(),
                request.riskSignals().stream().anyMatch(signal -> signal.relatesTo(rule.getJudgmentCategory())),
                evaluation, resolved, diagnostics);
    }

    @Transactional(readOnly = true)
    public RuleAnalysisResult analyze(RuleAnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        var rules = selector.select(request.confirmedIngredientMasterIds());
        var evaluated = rules.stream().map(rule -> new EvaluatedRule(rule, registry.evaluate(rule, request))).toList();
        var sourceRuleIds = evaluated.stream()
                .filter(item -> item.evaluation().status() != RuleEvaluation.Status.NOT_MATCHED)
                .map(item -> item.rule().getId()).toList();
        var sources = sourceResolver.resolve(sourceRuleIds);
        var matches = evaluated.stream().map(item -> {
            var rule = item.rule();
            var resolved = sources.getOrDefault(rule.getId(), List.of());
            List<Diagnostic> diagnostics = new ArrayList<>();
            if (item.evaluation().status() != RuleEvaluation.Status.NOT_MATCHED && resolved.isEmpty()) {
                diagnostics.add(Diagnostic.SOURCE_MISSING);
            }
            if ("DRAFT".equals(rule.getReviewStatus())) diagnostics.add(Diagnostic.DRAFT_RULE);
            return new RuleMatch(request.claim().id(), rule.getId(), rule.getRuleCode(), rule.getRuleVersion(),
                    rule.getScopeType(), rule.getJudgmentCategory(), rule.getSeverity(), rule.getReviewStatus(),
                    request.riskSignals().stream().anyMatch(signal -> signal.relatesTo(rule.getJudgmentCategory())), item.evaluation(),
                    resolved, diagnostics);
        }).toList();
        return new RuleAnalysisResult(request.claim().id(), matches,
                request.confirmedIngredientMasterIds().isEmpty()
                        ? List.of(Diagnostic.INGREDIENT_SPECIFIC_NOT_EVALUATED) : List.of(), request.riskSignals());
    }
}
