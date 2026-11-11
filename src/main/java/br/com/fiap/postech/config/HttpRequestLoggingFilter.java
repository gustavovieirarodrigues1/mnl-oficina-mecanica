package br.com.fiap.postech.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Registra apenas a rota normalizada; nao registra tokens, query strings ou corpos. */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("http.route", route == null ? "UNMATCHED" : route.toString())
                    .addKeyValue("http.response.status_code", failed ? 500 : response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0)
                    .log("HTTP request completed");
        }
    }
}
