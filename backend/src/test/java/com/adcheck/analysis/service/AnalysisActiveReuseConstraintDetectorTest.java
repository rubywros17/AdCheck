package com.adcheck.analysis.service;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisActiveReuseConstraintDetectorTest {

    private final AnalysisActiveReuseConstraintDetector detector =
            new AnalysisActiveReuseConstraintDetector();

    @Test
    void identifiesHibernateConstraintNameFromNestedCause() {
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "insert failed",
                new SQLException("duplicate key", "23505"),
                "insert into analyses ...",
                "uk_analyses_active_result_reuse"
        );
        DataIntegrityViolationException springException =
                new DataIntegrityViolationException("could not execute statement", hibernateException);

        assertThat(detector.isActiveReuseConflict(springException)).isTrue();
    }

    @Test
    void rejectsDifferentIntegrityConstraint() {
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "insert failed",
                new SQLException("duplicate key", "23505"),
                "insert into analyses ...",
                "fk_analyses_product"
        );

        assertThat(detector.isActiveReuseConflict(hibernateException)).isFalse();
    }
}
