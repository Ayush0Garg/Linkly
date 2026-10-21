package com.ayush.shortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link RateLimited} policies. It runs after Spring Security, so a request that fails CSRF or authentication
 * is turned away first and never uses up anyone's allowance, and the signed-in user is known.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !limiter.enabled()) {
            return true;
        }
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        if (limited == null) {
            return true;
        }
        Decision decision = limiter.require(limited.policy(), subject(limited, request));
        response.setHeader("RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
        return true;
    }

    static String subject(RateLimited limited, HttpServletRequest request) {
        if (limited.by() == RateLimited.By.USER) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken token) {
                return "user:" + token.getName();
            }
        }
        return "ip:" + ClientIps.key(request.getRemoteAddr());
    }
}
