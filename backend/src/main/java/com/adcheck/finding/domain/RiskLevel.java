package com.adcheck.finding.domain;

import java.util.Locale;

public enum RiskLevel {
    HIGH,
    CAUTION,
    NORMAL;

    /**
     * Rule Engine의 {@code severity} 문자열("HIGH"/"CAUTION"/"NORMAL")을 {@link RiskLevel}로
     * 변환한다. 알 수 없는 값이나 {@code null}이 오면 예외를 던지지 않고 {@link #CAUTION}으로
     * 안전하게 처리한다 — 광고 위반 여부를 판단하는 값이라, 모르는 값을 조용히 무시하거나
     * {@link #NORMAL}로 낮잡는 것보다 사람이 확인하도록 주의 단계로 남기는 쪽이 안전하다
     * (extension의 {@code getCategoryTheme()} fallback과 동일한 정책).
     */
    public static RiskLevel fromSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return CAUTION;
        }
        try {
            return RiskLevel.valueOf(severity.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CAUTION;
        }
    }
}
