package com.kholodilin.repogrowth.auth.api;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final UserAccountService users;
    private final AuthProvider provider;

    public MeController(UserAccountService users, AuthProvider provider) {
        this.users = users;
        this.provider = provider;
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        String loginUrl = provider == AuthProvider.MOCK ? "/login" : "/oauth2/authorization/oidc";
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return MeResponse.anonymous(loginUrl);
        }
        AppUser user = users.findById(principal.userId()).orElse(null);
        if (user == null || !user.enabled()) {
            return MeResponse.anonymous(loginUrl);
        }
        List<String> roles = new ArrayList<>();
        for (AppRole role : user.roles()) {
            roles.add(role.name());
        }
        List<String> actions = new ArrayList<>();
        if (user.isAdmin()) {
            actions.add("MUTATE");
        }
        return new MeResponse(
                true,
                String.valueOf(user.id()),
                user.displayName(),
                roles,
                actions,
                loginUrl,
                "/logout"
        );
    }

    public record MeResponse(
            boolean authenticated,
            String userId,
            String displayName,
            List<String> roles,
            List<String> actions,
            String loginUrl,
            String logoutUrl
    ) {
        static MeResponse anonymous(String loginUrl) {
            return new MeResponse(false, null, null, List.of(), List.of(), loginUrl, "/logout");
        }
    }
}
