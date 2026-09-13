package com.adcheck.analysis.result;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AnalysisResultJsonCodec {

    private final ObjectMapper objectMapper;

    public AnalysisResultJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(AnalysisResultSnapshot snapshot) {
        if (snapshot == null) {
            throw new AnalysisResultJsonException("저장할 분석 결과가 없습니다.");
        }

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new AnalysisResultJsonException("분석 결과를 JSON으로 변환하지 못했습니다.", exception);
        }
    }

    public AnalysisResultSnapshot deserialize(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            throw new AnalysisResultJsonException("복원할 분석 결과 JSON이 없습니다.");
        }

        try {
            AnalysisResultSnapshot snapshot =
                    objectMapper.readValue(resultJson, AnalysisResultSnapshot.class);
            if (snapshot == null) {
                throw new AnalysisResultJsonException("분석 결과 JSON이 null입니다.");
            }
            return snapshot;
        } catch (JacksonException exception) {
            throw new AnalysisResultJsonException("분석 결과 JSON을 복원하지 못했습니다.", exception);
        }
    }
}
