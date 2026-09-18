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
 * 정규식 9개 + AI 20개(1차 9개: 3회 반복 9/9 안정 정답, 2차 7개: "왔다갔다"하던 것을 2회 더
 * 재실행해 5회 중 4~5회 정답으로 확인된 것, 3차 3개: 패턴1 프롬프트 수정 후 3회 반복 실행에서
 * 8~9/9로 안정적으로 정답, 4차 1개: 패턴2 프롬프트 수정 후 서로 다른 문구 버전 2개에서 연속
 * 3/3) = 32개. allowlist에 없는 코드는 evaluator가 있어도 무시되고 {@code UNSUPPORTED_RULE}로
 * 남는다.
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
            "E02_OTHER_FUNCTION", "E03_GENERATION", "O02_ENERGY_EXPANSION", "P03_DISEASE_GUT",
            // AiRuleEvaluator 3차 확장(패턴1 프롬프트 수정 후 3회 반복 실행 8~9/9로 안정) — 3개
            "E01_VESSEL", "L03_EYE_DISEASE", "C22_SUPERLATIVE",
            // AiRuleEvaluator 4차 확장(패턴2 "핵심 단어=자동매치 아님" 수정 후 서로 다른 프롬프트
            // 버전 2개에서 연속 3/3) — 1개
            "L01_VISION"
    );

    /**
     * 규칙 판정을 동시에 몇 개까지 진행할지. 규칙 하나당 호출 1회(모든 Claim 배치)이므로 이 값이
     * 곧 동시 Gemini 호출 수다 — 무료 티어 분당 한도(15회)를 한꺼번에 소진하지 않도록 보수적으로
     * 잡는다. 429가 나도 {@code GeminiClient}가 재시도하므로 치명적이진 않지만, 재시도 대기가
     * 오히려 전체를 느리게 만들 수 있다.
     */
    private int concurrency = 4;

    public List<String> getEnabledRuleCodes() {
        return enabledRuleCodes;
    }

    public void setEnabledRuleCodes(List<String> enabledRuleCodes) {
        this.enabledRuleCodes = enabledRuleCodes;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }
}
