package com.kholodilin.repogrowth.identity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.kholodilin.repogrowth.identity.config.AuthProvider;
import com.kholodilin.repogrowth.identity.controller.MeController;
import com.kholodilin.repogrowth.identity.model.AppRole;
import com.kholodilin.repogrowth.identity.model.AppUser;
import com.kholodilin.repogrowth.identity.security.AppUserPrincipal;
import com.kholodilin.repogrowth.identity.service.UserAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

class MeControllerTest {

  UserAccountService users;
  MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    users = mock(UserAccountService.class);
    mockMvc = standaloneSetup(new MeController(users, AuthProvider.MOCK)).build();
  }

  @Test
  void anonymousForMissingDisabledOrWrongPrincipal() throws Exception {
    mockMvc
        .perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.loginUrl").value("/login"));

    var wrong = UsernamePasswordAuthenticationToken.authenticated("x", null, List.of());
    mockMvc
        .perform(get("/api/v1/me").principal(wrong))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));

    AppUserPrincipal principal = new AppUserPrincipal(9L, "Gone");
    var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    when(users.findById(9L)).thenReturn(Optional.empty());
    mockMvc
        .perform(get("/api/v1/me").principal(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));

    when(users.findById(9L))
        .thenReturn(
            Optional.of(
                new AppUser(
                    9L,
                    "mock",
                    "gone",
                    "Gone",
                    false,
                    null,
                    Instant.now(),
                    Instant.now(),
                    List.of(AppRole.USER))));
    mockMvc
        .perform(get("/api/v1/me").principal(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));
  }

  @Test
  void authenticatedAdminIncludesMutate() throws Exception {
    AppUser admin =
        new AppUser(
            1L,
            "mock",
            "admin",
            "Admin",
            true,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            List.of(AppRole.USER, AppRole.ADMIN));
    when(users.findById(1L)).thenReturn(Optional.of(admin));
    AppUserPrincipal principal = new AppUserPrincipal(1L, "Admin");
    var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());

    mockMvc
        .perform(get("/api/v1/me").principal(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.actions[0]").value("MUTATE"))
        .andExpect(jsonPath("$.loginUrl").value("/login"));
  }
}
