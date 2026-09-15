package com.adcheck.analysis.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 네이버 스마트에디터 상세페이지는 문단마다 빈 줄을 끼워넣는 습관이 있어(실제 상품
 * 상세텍스트 표본 기준 3,946자 중 206곳), LLM에 넘기기 전 정보 손실 없이 압축해둔다.
 */
@Component
public class DetailTextCleaner {

    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n[ \\t\\u00A0]*\\n+");
    private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("[ \\t\\u00A0]+\\n");

    public String clean(String detailText) {
        if (detailText == null) {
            return null;
        }

        String normalized = detailText.replace("\r\n", "\n").replace("\r", "\n");
        normalized = TRAILING_LINE_WHITESPACE.matcher(normalized).replaceAll("\n");
        normalized = BLANK_LINE_RUN.matcher(normalized).replaceAll("\n");

        return normalized.trim();
    }
}
