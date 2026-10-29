package com.kholodilin.repogrowth.auth.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

@Component
public class MockLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final Clock clock;

    public MockLoginSuccessHandler(Clock clock) {
        this.clock = clock;
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(true);
        session.setAttribute(AbsoluteSessionTimeoutFilter.CREATED_AT_ATTR, Instant.now(clock));
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
