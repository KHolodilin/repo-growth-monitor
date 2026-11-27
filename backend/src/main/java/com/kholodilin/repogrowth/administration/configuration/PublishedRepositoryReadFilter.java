package com.kholodilin.repogrowth.administration.configuration;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.auth.security.CurrentUserAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anonymous GETs under /api/v1/admin/repositories/{id}/** are allowed only for published
 * dashboards. Admins keep full read access. Mutations remain blocked by HTTP security config.
 */
@Component
public class PublishedRepositoryReadFilter extends OncePerRequestFilter {

  private static final Pattern REPO =
      Pattern.compile("^/api/v1/admin/repositories/(\\d+)(?:/.*)?$");

  private final CurrentUserAccess currentUserAccess;
  private final PublicationService publicationService;
  private final JsonMapper json;

  public PublishedRepositoryReadFilter(
      CurrentUserAccess currentUserAccess, PublicationService publicationService, JsonMapper json) {
    this.currentUserAccess = currentUserAccess;
    this.publicationService = publicationService;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (HttpMethod.GET.matches(request.getMethod())) {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      Matcher matcher = REPO.matcher(path);
      if (matcher.matches() && !currentUserAccess.canSeePrivateRepositories()) {
        long repositoryId = Long.parseLong(matcher.group(1));
        if (!publicationService.allows(repositoryId, PublicationService.Module.DASHBOARD)) {
          response.setStatus(HttpStatus.NOT_FOUND.value());
          response.setCharacterEncoding(StandardCharsets.UTF_8.name());
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          json.writeValue(
              response.getWriter(),
              json.createObjectNode()
                  .put("code", "NOT_FOUND")
                  .put("message", "Repository not found"));
          return;
        }
      }
    }
    filterChain.doFilter(request, response);
  }
}
