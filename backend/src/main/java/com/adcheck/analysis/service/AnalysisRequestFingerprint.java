package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class AnalysisRequestFingerprint {

    private static final Pattern CONSECUTIVE_WHITESPACE = Pattern.compile("\\s+");
    private static final Comparator<CanonicalText> TEXT_ORDER =
            Comparator.comparing(CanonicalText::content)
                    .thenComparing(CanonicalText::selector);
    private static final Comparator<CanonicalImage> IMAGE_ORDER =
            Comparator.comparing(CanonicalImage::url)
                    .thenComparing(CanonicalImage::alt);

    public String generate(CreateAnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("분석 요청은 null일 수 없습니다.");
        }

        MessageDigest digest = sha256();
        updateField(digest, "productName", normalize(request.productName()));

        List<CanonicalText> texts = request.texts().stream()
                .map(this::canonicalize)
                .sorted(TEXT_ORDER)
                .toList();
        updateCollectionSize(digest, "texts", texts.size());
        for (CanonicalText text : texts) {
            // selector는 해시에 넣지 않는다. 화면 하이라이트 위치를 가리킬 뿐 분석 대상이 아니고,
            // 페이지를 열 때마다 쉽게 달라져 재사용 캐시를 통째로 무력화하기 때문이다.
            //
            // 실측(2026-09-27, i-hi.co.kr/product_no=111): 같은 페이지를 연달아 분석했는데 텍스트
            // 146개·이미지 24장이 내용까지 완전히 같은데도 해시가 갈렸다. 원인은 네이버페이 결제
            // 위젯 두 줄뿐이었다 — 하나는 id에 타임스탬프가 박혀 있고
            // (#NPAY_PROMOTION_IDNC_ID_1790436451926390), 다른 하나는 위젯 로딩 타이밍에 따라
            // nth-of-type 번호가 밀렸다(div:nth-of-type(7) → (6)).
            //
            // 그 탓에 캐시가 매번 빗나가 같은 페이지를 볼 때마다 새로 분석했고, AI#1의 편차가
            // 그대로 화면에 드러나 "새로고침할 때마다 검출 문구 수가 다르다"는 증상이 됐다.
            // 결제 위젯만의 문제가 아니라 광고·배너·챗봇 등 무엇이든 nth-of-type을 밀 수 있어,
            // 특정 패턴을 막기보다 selector를 해시에서 빼는 쪽이 근본적이다.
            //
            // 정렬 기준(TEXT_ORDER)에는 selector를 그대로 둔다 — 내용이 같은 항목끼리의 순서만
            // 가르는 용도라, 순서가 바뀌어도 해시에 들어가는 content 열은 동일하다.
            updateField(digest, "text.content", text.content());
        }

        List<CanonicalImage> images = request.images().stream()
                .map(this::canonicalize)
                .sorted(IMAGE_ORDER)
                .toList();
        updateCollectionSize(digest, "images", images.size());
        for (CanonicalImage image : images) {
            updateField(digest, "image.url", image.url());
            updateField(digest, "image.alt", image.alt());
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private CanonicalText canonicalize(PageTextEvidence text) {
        if (text == null) {
            return new CanonicalText("", "");
        }
        return new CanonicalText(normalize(text.content()), normalize(text.selector()));
    }

    private CanonicalImage canonicalize(PageImageEvidence image) {
        if (image == null) {
            return new CanonicalImage("", "");
        }
        return new CanonicalImage(normalize(image.url()), normalize(image.alt()));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return CONSECUTIVE_WHITESPACE.matcher(value.strip()).replaceAll(" ");
    }

    private void updateCollectionSize(MessageDigest digest, String name, int size) {
        updateLengthPrefixed(digest, name);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(size).array());
    }

    private void updateField(MessageDigest digest, String name, String value) {
        updateLengthPrefixed(digest, name);
        updateLengthPrefixed(digest, value);
    }

    private void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private record CanonicalText(String content, String selector) {
    }

    private record CanonicalImage(String url, String alt) {
    }
}
