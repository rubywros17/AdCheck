package com.adcheck.rule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 실제로 자동 판정을 켤 규칙 코드 allowlist. {@code AiRuleEvaluator}/{@code LiteralRuleEvaluator}는
 * 각자 "판정 가능한" 전체 규칙 집합(39개/9개)을 {@code ruleCodes()}로 선언하지만, 그중
 * 실제로 {@code RuleEvaluatorRegistry}에 등록해 파이프라인에 연결할지는 이 allowlist가
 * 최종 결정한다 — 검증되지 않은 규칙까지 한꺼번에 켜지 않고 단계적으로 넓혀가기 위함.
 *
 * <p>기본값은 2026-09-17 파일럿(검증 데이터셋 반복 실행으로 검증된 것만) — Common 3개 +
 * 정규식 9개 + AI 16개(1차 9개: 3회 반복 9/9 안정 정답, 2차 7개: "왔다갔다"하던 것을 2회 더
 * 재실행해 5회 중 4~5회 정답으로 확인된 것) = 28개. allowlist에 없는 코드는 evaluator가
 * 있어도 무시되고 {@code UNSUPPORTED_RULE}로 남는다.
 */
@Component
@ConfigurationProperties(prefix = "adcheck.rule-judge")
public class RuleJudgeProperties {

    private List<String> enabledRuleCodes = List.of(
            // 기존 운영 중(Common, 정규식) — 3개
            "C05_FUNCTION_EXCEED", "C07_ABSOLUTE_EFFECT", "C24_OVERCONSUMPTION",
            // LiteralRuleEvaluator(정규식, LLM 노이즈 없어 검증 완료) — 9개
            "C08_RESULT_TIME_AMOUNT", "C09_COMPLETE_SOLUTION", "G02_PERIOD", "T01_WEIGHT_RESULT",
            "T02_DETOX", "T03_DIET_DRUG", "B05_ANTIBACTERIAL_WORD", "M03_ALCOHOL", "S01_PAIN",
            // AiRuleEvaluator 1차 파일럿(검증 데이터셋 3회 반복 실행 9/9 안정적으로 정답) — 9개
            "B02_VIRUS", "C27_FUNCTION_SYNERGY", "G03_WEIGHT_FAT", "G04_SATIETY_COFFEE",
            "O01_SEXUAL", "P05_INFANT", "R01_COLD", "R02_MENOPAUSE", "T04_ANTIAGING",
            // AiRuleEvaluator 2차 확장(원래 "왔다갔다"했으나 2회 재실행 후 5회 중 4~5회 정답) — 7개
            "C01_DISEASE_PREVENTION", "C02_DISEASE_TREATMENT", "P01_VAGINAL_SCOPE",
            "E02_OTHER_FUNCTION", "E03_GENERATION", "O02_ENERGY_EXPANSION", "P03_DISEASE_GUT"
    );

    public List<String> getEnabledRuleCodes() {
        return enabledRuleCodes;
    }

    public void setEnabledRuleCodes(List<String> enabledRuleCodes) {
        this.enabledRuleCodes = enabledRuleCodes;
    }
}
