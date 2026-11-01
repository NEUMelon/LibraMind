package com.libramind.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 检查请求编号的边界与线程日志清理，防止未校验的请求头污染响应和日志。 */
class RequestIdFilterTest {

    @Test
    void preservesSafeIdentifierAndClearsLoggingContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "test-request_123");
        new RequestIdFilter()
                .doFilter(
                        request,
                        response,
                        (req, res) ->
                                assertThat(MDC.get("requestId")).isEqualTo("test-request_123"));
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("test-request_123");
        assertThat(RequestIdFilter.id(request)).isEqualTo("test-request_123");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void replacesUnsafeHeaderAndStillClearsContextOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "bad\nheader");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new RequestIdFilter()
                                        .doFilter(
                                                request,
                                                response,
                                                (req, res) -> {
                                                    throw new IllegalStateException("test failure");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(response.getHeader("X-Request-Id")).matches("[a-f0-9-]{36}");
        assertThat(MDC.get("requestId")).isNull();
    }
}
