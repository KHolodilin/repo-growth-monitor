package com.kholodilin.repogrowth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kholodilin.repogrowth.identity.mock.MockUserCatalog;
import com.kholodilin.repogrowth.identity.model.AppUser;
import com.kholodilin.repogrowth.identity.security.AppUserPrincipal;
import com.kholodilin.repogrowth.identity.security.SecurityAuthorities;
import com.kholodilin.repogrowth.identity.service.UserAccountService;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties = {
      "rgm.orchestration.enabled=true",
      "rgm.orchestration.nodes[0].key=collector",
      "rgm.orchestration.nodes[0].pool=default",
      "rgm.orchestration.nodes[0].endpoint=https://collector.example",
      "rgm.orchestration.nodes[0].enabled=true"
    })
@AutoConfigureMockMvc
class AuthSecurityIT extends AbstractPostgresTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserAccountService users;
  @Autowired MockUserCatalog catalog;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void syncUsers() {
    catalog.syncMissingUsers();
  }

  @Test
  void meIsAnonymousForGuest() throws Exception {
    mockMvc
        .perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.loginUrl").value("/login"));
  }

  @Test
  void guestCannotMutate() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/collection/plan").with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void userCannotMutate() throws Exception {
    AppUser user = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "user").orElseThrow();
    mockMvc
        .perform(post("/api/v1/admin/collection/plan").with(csrf()).with(asPrincipal(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void adminCanMutate() throws Exception {
    AppUser admin = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "admin").orElseThrow();
    assertThat(admin.isAdmin()).isTrue();
    mockMvc
        .perform(post("/api/v1/admin/collection/plan").with(csrf()).with(asPrincipal(admin)))
        .andExpect(status().isCreated());
  }

  @Test
  void revokeAdminAppliesWithoutRelogin() throws Exception {
    AppUser admin = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "admin").orElseThrow();
    users.revokeAdmin(admin.id());
    try {
      mockMvc
          .perform(post("/api/v1/admin/collection/plan").with(csrf()).with(asPrincipal(admin)))
          .andExpect(status().isForbidden());
    } finally {
      users.grantAdmin(admin.id());
    }
  }

  @Test
  void mockLoginSetsSessionAndMe() throws Exception {
    MvcResult login =
        mockMvc
            .perform(
                post("/login").param("username", "admin").param("password", "admin").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
    mockMvc
        .perform(get("/api/v1/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.displayName").value("Mock Admin"))
        .andExpect(jsonPath("$.actions[0]").value("MUTATE"));
  }

  @Test
  void systemStatusIsAdminOnly() throws Exception {
    mockMvc.perform(get("/api/v1/admin/system/status")).andExpect(status().isUnauthorized());
  }

  @Test
  void loginPageAndAuthenticatedMe() throws Exception {
    mockMvc.perform(get("/login")).andExpect(status().isOk());
    AppUser admin = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "admin").orElseThrow();
    mockMvc
        .perform(get("/api/v1/me").with(asPrincipal(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.actions[0]").value("MUTATE"));
    AppUser user = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "user").orElseThrow();
    mockMvc
        .perform(get("/api/v1/me").with(asPrincipal(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.actions").isEmpty());
  }

  private static RequestPostProcessor asPrincipal(AppUser user) {
    AppUserPrincipal principal = new AppUserPrincipal(user.id(), user.displayName());
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, SecurityAuthorities.from(user)));
  }
}
