package com.kholodilin.repogrowth.auth.application;

import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.persistence.AppUserJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class UserAccountService {

    private final AppUserJdbcRepository users;
    private final Clock clock;

    public UserAccountService(AppUserJdbcRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public Optional<AppUser> findById(long id) {
        return users.findById(id);
    }

    public Optional<AppUser> findByIssuerAndSub(String issuer, String sub) {
        return users.findByIssuerAndSub(issuer, sub);
    }

    public List<AppUser> list() {
        return users.findAll();
    }

    @Transactional
    public AppUser ensureOnLogin(String issuer, String sub, String displayName) {
        Optional<AppUser> existing = users.findByIssuerAndSub(issuer, sub);
        AppUser user = existing.orElseGet(() -> users.insertUser(issuer, sub, blankToSub(displayName, sub)));
        users.ensureRole(user.id(), AppRole.USER);
        users.touchLogin(user.id(), Instant.now(clock));
        return users.findById(user.id()).orElseThrow();
    }

    @Transactional
    public AppUser ensureMockUser(String issuer, String sub, String displayName) {
        return users.findByIssuerAndSub(issuer, sub)
                .orElseGet(() -> {
                    AppUser created = users.insertUser(issuer, sub, blankToSub(displayName, sub));
                    users.ensureRole(created.id(), AppRole.USER);
                    // First insert only: mock username "admin" starts as ADMIN. Later CLI revoke/grant wins.
                    if ("admin".equals(sub)) {
                        users.ensureRole(created.id(), AppRole.ADMIN);
                    }
                    return users.findById(created.id()).orElseThrow();
                });
    }

    @Transactional
    public AppUser grantAdmin(long userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        users.ensureRole(user.id(), AppRole.USER);
        users.ensureRole(user.id(), AppRole.ADMIN);
        return users.findById(userId).orElseThrow();
    }

    @Transactional
    public AppUser revokeAdmin(long userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        users.revokeRole(user.id(), AppRole.ADMIN);
        users.ensureRole(user.id(), AppRole.USER);
        return users.findById(userId).orElseThrow();
    }

    public boolean canMutate(AppUser user) {
        return user != null && user.enabled() && user.isAdmin();
    }

    private static String blankToSub(String displayName, String sub) {
        if (displayName == null || displayName.isBlank()) {
            return sub;
        }
        return displayName.trim();
    }
}
