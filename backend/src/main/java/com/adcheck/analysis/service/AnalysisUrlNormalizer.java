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

    private static final Set<String> TRACKING_QUERY_PARAMETERS = Set.of(
            "utm_source",
            "utm_medium",
            "utm_campaign",
            "utm_term",
            "utm_content",
            "gclid",
            "fbclid"
    );

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
        return TRACKING_QUERY_PARAMETERS.contains(name);
    }

    private void appendHost(StringBuilder target, String host) {
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            target.append('[').append(host).append(']');
            return;
        }
        target.append(host);
    }
}
