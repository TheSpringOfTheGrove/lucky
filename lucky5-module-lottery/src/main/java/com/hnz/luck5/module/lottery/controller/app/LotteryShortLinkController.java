package com.hnz.luck5.module.lottery.controller.app;

import com.hnz.luck5.framework.tenant.core.aop.TenantIgnore;
import com.hnz.luck5.module.lottery.service.LotteryService;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Public legacy-compatible links. Nginx exposes this controller as /k/{code}. */
@RestController
@RequestMapping("/k")
@TenantIgnore
@PermitAll
public class LotteryShortLinkController {

    @Resource
    private LotteryService lotteryService;

    @GetMapping("/{code}")
    public void resolve(@PathVariable String code, HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        String destination = lotteryService.resolveMemberShortLink(code, resolveRequestOrigin(request));
        if (destination == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", destination);
    }

    private static String resolveRequestOrigin(HttpServletRequest request) {
        String scheme = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            scheme = request.getScheme();
        }
        String host = firstForwardedValue(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isBlank()) {
            host = firstForwardedValue(request.getHeader("Host"));
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && !(("http").equalsIgnoreCase(scheme) && port == 80)
                    && !(("https").equalsIgnoreCase(scheme) && port == 443)) {
                host += ":" + port;
            }
        }
        return scheme.toLowerCase() + "://" + host;
    }

    private static String firstForwardedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(",", 2)[0].trim();
    }
}
