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
            updateField(digest, "text.content", text.content());
            updateField(digest, "text.selector", text.selector());
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
