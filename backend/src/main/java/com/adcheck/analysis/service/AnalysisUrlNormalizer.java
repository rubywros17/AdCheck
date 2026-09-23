package com.adcheck.analysis.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AnalysisUrlNormalizer {

    /**
     * 광고 유입 추적용 파라미터 — 페이지 내용과 무관하므로 재사용 키에서 뺀다.
     *
     * <p>이 목록에서 빠지면 <b>캐시가 통째로 무력화된다</b>. {@code gbraid}·{@code srsltid}처럼
     * 클릭할 때마다 값이 바뀌는 것들이 있어서, 광고로 유입된 사용자는 같은 상품 페이지를 볼
     * 때마다 매번 새 분석이 돌아간다(건당 Gemini 13~16콜, 약 20초). 실제로 저장된 분석 34건의
     * {@code normalized_url}을 훑어보니 {@code gad_source}·{@code gad_campaignid}·{@code gbraid}가
     * 각 14건, {@code utm_id} 5건, {@code srsltid} 3건이 그대로 남아 있었다(2026-09-23 실측).
     *
     * <p>반대로 {@code product_no}·{@code goodsNo}·{@code branduid}처럼 <b>어느 상품인지를
     * 결정하는 값</b>은 절대 지우면 안 된다 — 서로 다른 상품이 같은 URL로 합쳐진다. 그래서
     * "남길 것을 고르는" 방식이 아니라 "지울 것만 명시하는" 방식을 유지한다.
     */
    private static final Set<String> TRACKING_QUERY_PARAMETERS = Set.of(
            "gclid",
            "fbclid",
            // 구글 광고 — 실측에서 살아남은 것들
            "gad_source",
            "gad_campaignid",
            "gbraid",
            "wbraid",
            "srsltid",
            // 그 외 매체
            "msclkid",
            "ttclid",
            "yclid",
            "igshid"
    );

    /**
     * 이 접두어로 시작하는 파라미터는 이름을 일일이 나열하지 않고 전부 제거한다.
     *
     * <p>UTM은 표준 규약이라 변형이 계속 늘어난다 — 기존 목록에 {@code utm_source}/{@code
     * utm_medium}/{@code utm_campaign}/{@code utm_term}/{@code utm_content} 다섯 개만 있어서
     * GA4가 쓰는 {@code utm_id}가 그대로 통과했다. 하나씩 추가하면 다음 변형에서 또 놓치므로
     * 접두어로 막는다. 상품 식별자에 {@code utm_}을 쓰는 쇼핑몰은 사실상 없다.
     */
    private static final Set<String> TRACKING_QUERY_PREFIXES = Set.of("utm_");

    public String normalize(String value) {
        try {
            URI uri = new URI(value);
            String scheme = normalizeScheme(uri.getScheme());
            String host = normalizeHost(uri.getHost());
            int port = normalizePort(scheme, uri.getPort());
            String query = removeTrackingParameters(uri.getRawQuery());

            StringBuilder normalized = new StringBuilder();
            normalized.append(scheme).append("://");
            if (uri.getRawUserInfo() != null) {
                normalized.append(uri.getRawUserInfo()).append('@');
            }
            appendHost(normalized, host);
            if (port != -1) {
                normalized.append(':').append(port);
            }
            if (uri.getRawPath() != null) {
                normalized.append(uri.getRawPath());
            }
            if (query != null) {
                normalized.append('?').append(query);
            }
            return normalized.toString();
        } catch (URISyntaxException | NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "분석 URL은 올바른 HTTP 또는 HTTPS URL이어야 합니다.",
                    exception
            );
        }
    }

    private String normalizeScheme(String scheme) {
        if (scheme == null) {
            throw new IllegalArgumentException("URL scheme이 필요합니다.");
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (!normalized.equals("http") && !normalized.equals("https")) {
            throw new IllegalArgumentException("HTTP 또는 HTTPS URL만 지원합니다.");
        }
        return normalized;
    }

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host가 필요합니다.");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private int normalizePort(String scheme, int port) {
        if ((scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443)) {
            return -1;
        }
        return port;
    }

    private String removeTrackingParameters(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        String filteredQuery = Arrays.stream(rawQuery.split("&", -1))
                .filter(parameter -> !isTrackingParameter(parameter))
                .collect(Collectors.joining("&"));
        return filteredQuery.isEmpty() ? null : filteredQuery;
    }

    private boolean isTrackingParameter(String rawParameter) {
        int separator = rawParameter.indexOf('=');
        String rawName = separator >= 0 ? rawParameter.substring(0, separator) : rawParameter;
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return TRACKING_QUERY_PARAMETERS.contains(name)
                || TRACKING_QUERY_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private void appendHost(StringBuilder target, String host) {
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            target.append('[').append(host).append(']');
            return;
        }
        target.append(host);
    }
}
