package com.kholodilin.repogrowth.publication;

import com.kholodilin.repogrowth.auth.security.CurrentUserAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymous GETs under /api/v1/admin/repositories/{id}/** are allowed only for published dashboards.
 * Admins keep full read access. Mutations remain blocked by HTTP security config.
 */
@Component
public class PublishedRepositoryReadFilter extends OncePerRequestFilter {

    private static final Pattern REPO = Pattern.compile("^/api/v1/admin/repositories/(\\d+)(?:/.*)?$");

    private final CurrentUserAccess currentUserAccess;
    private final PublicationService publicationService;

    public PublishedRepositoryReadFilter(CurrentUserAccess currentUserAccess, PublicationService publicationService) {
        this.currentUserAccess = currentUserAccess;
        this.publicationService = publicationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (HttpMethod.GET.matches(request.getMethod())) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            Matcher matcher = REPO.matcher(path);
            if (matcher.matches() && !currentUserAccess.canSeePrivateRepositories()) {
                long repositoryId = Long.parseLong(matcher.group(1));
                if (!publicationService.allows(repositoryId, PublicationService.Module.DASHBOARD)) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"code\":\"NOT_FOUND\",\"message\":\"Repository not found\"}");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
