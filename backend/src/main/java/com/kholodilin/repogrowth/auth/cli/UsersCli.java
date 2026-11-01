package com.kholodilin.repogrowth.auth.cli;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;

import java.util.Locale;
import java.util.stream.Collectors;

public final class UsersCli {

    private final UserAccountService users;

    public UsersCli(UserAccountService users) {
        this.users = users;
    }

    public int run(String[] args) {
        if (args.length < 2) {
            printUsage();
            return 2;
        }
        String command = args[1].trim().toLowerCase(Locale.ROOT);
        return switch (command) {
            case "list" -> list();
            case "grant-admin" -> mutateAdmin(args, true);
            case "revoke-admin" -> mutateAdmin(args, false);
            default -> {
                System.err.println("Unknown users command: " + args[1]);
                printUsage();
                yield 2;
            }
        };
    }

    private int list() {
        System.out.printf("%-8s %-24s %-32s %-8s %-16s %s%n",
                "ID", "DISPLAY", "SUB", "ENABLED", "ROLES", "ISSUER");
        for (AppUser user : users.list()) {
            String roles = user.roles().stream().map(AppRole::name).collect(Collectors.joining(","));
            System.out.printf("%-8d %-24s %-32s %-8s %-16s %s%n",
                    user.id(),
                    truncate(user.displayName(), 24),
                    truncate(user.oidcSub(), 32),
                    user.enabled(),
                    roles,
                    user.oidcIssuer());
        }
        return 0;
    }

    private int mutateAdmin(String[] args, boolean grant) {
        Long userId = parseUserId(args);
        if (userId == null) {
            printUsage();
            return 2;
        }
        try {
            AppUser user = grant ? users.grantAdmin(userId) : users.revokeAdmin(userId);
            System.out.printf("%s ADMIN for user %d (%s). Roles: %s%n",
                    grant ? "Granted" : "Revoked",
                    user.id(),
                    user.displayName(),
                    user.roles().stream().map(AppRole::name).collect(Collectors.joining(",")));
            return 0;
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            return 1;
        }
    }

    private static Long parseUserId(String[] args) {
        for (int i = 2; i < args.length; i++) {
            if ("--user-id".equals(args[i]) && i + 1 < args.length) {
                try {
                    return Long.parseLong(args[i + 1]);
                } catch (NumberFormatException ex) {
                    System.err.println("Invalid --user-id: " + args[i + 1]);
                    return null;
                }
            }
        }
        System.err.println("Missing --user-id");
        return null;
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  users list");
        System.err.println("  users grant-admin --user-id <id>");
        System.err.println("  users revoke-admin --user-id <id>");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
