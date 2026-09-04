package com.adcheck.analysis.controller;

import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.service.AnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody CreateAnalysisRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(analysisService.analyze(request));
    }
}
