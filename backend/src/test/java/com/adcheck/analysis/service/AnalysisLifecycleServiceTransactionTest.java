package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisLifecycleServiceTransactionTest {

    @Test
    void lifecycleOperationsUseRequiresNewTransactionsOnSeparateBean() throws Exception {
        assertRequiresNew("createPending", com.adcheck.analysis.dto.CreateAnalysisRequest.class, AnalysisReuseKey.class);
        assertRequiresNew("markProcessing", Long.class);
        assertRequiresNew("completeWithResult", Long.class, String.class, boolean.class);
        assertRequiresNew("fail", Long.class, String.class);
        assertRequiresNew("findActive", AnalysisReuseKey.class);
        assertThat(AnalysisLifecycleService.class).isNotEqualTo(AnalysisService.class);
    }

    private void assertRequiresNew(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AnalysisLifecycleService.class.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
