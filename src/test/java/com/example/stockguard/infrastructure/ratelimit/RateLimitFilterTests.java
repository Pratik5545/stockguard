package com.example.stockguard.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTests {
    @Test
    void directConnectionsAndAmbiguousEdgeHeadersUseTheSocketPeer() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("CF-Connecting-IP", "198.51.100.1");
        assertThat(RateLimitFilter.authClientAddress(request, false)).isEqualTo("192.0.2.10");
        request.addHeader("CF-Connecting-IP", "198.51.100.2");
        assertThat(RateLimitFilter.authClientAddress(request, true)).isEqualTo("192.0.2.10");
        request.removeHeader("CF-Connecting-IP");
        request.addHeader("CF-Connecting-IP", "999.100.5.80");
        assertThat(RateLimitFilter.authClientAddress(request, true)).isEqualTo("192.0.2.10");
    }
}
