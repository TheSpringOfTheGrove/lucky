package com.hnz.luck5.module.lottery.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryControllerLinkOriginTest {

    @Test
    void resolvesPublicHttpsOriginBehindReverseProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("server");
        request.setServerPort(48080);
        request.addHeader("Host", "server:48080");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "lucky555.930i.xyz");

        assertThat(LotteryController.resolveRequestOrigin(request))
                .isEqualTo("https://lucky555.930i.xyz");
    }

    @Test
    void keepsLocalBrowserPortWithoutProxyHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addHeader("Host", "localhost:8080");

        assertThat(LotteryController.resolveRequestOrigin(request))
                .isEqualTo("http://localhost:8080");
    }
}
