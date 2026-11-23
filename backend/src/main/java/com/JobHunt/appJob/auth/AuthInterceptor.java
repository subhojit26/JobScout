package com.JobHunt.appJob.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Requires a valid {@code Authorization: Bearer <token>} header on every {@code /api/**} call
 * except sign-up and login. The signed-in user's id is exposed to controllers as the request
 * attribute {@code userId}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID = "userId";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true; // CORS preflight
        }
        Optional<Long> userId = authService.authenticate(bearerToken(request));
        if (userId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Please sign in.\"}");
            return false;
        }
        request.setAttribute(USER_ID, userId.get());
        return true;
    }

    static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }
}
