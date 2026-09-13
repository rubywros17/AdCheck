package com.adcheck.analysis.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AnalysisActiveReuseConstraintDetector {

    static final String CONSTRAINT_NAME = "uk_analyses_active_result_reuse";

    public boolean isActiveReuseConflict(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && CONSTRAINT_NAME.equalsIgnoreCase(constraintViolation.getConstraintName())) {
                return true;
            }
            if (containsConstraintName(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsConstraintName(String message) {
        return message != null
                && message.toLowerCase(Locale.ROOT).contains(CONSTRAINT_NAME);
    }
}
