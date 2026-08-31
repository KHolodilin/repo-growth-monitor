package com.kholodilin.repogrowth.event.detect;

public final class ExternalActor {

    private ExternalActor() {
    }

    public static boolean isExternal(String login, String userType, String ownerLogin) {
        if (login == null || login.isBlank() || ownerLogin == null || ownerLogin.isBlank()) {
            return false;
        }
        if (isBot(userType, login)) {
            return false;
        }
        return !login.equalsIgnoreCase(ownerLogin);
    }

    public static boolean isBot(String userType, String login) {
        if (userType != null) {
            String type = userType.trim();
            if ("Bot".equalsIgnoreCase(type) || "App".equalsIgnoreCase(type)) {
                return true;
            }
        }
        return login != null && login.toLowerCase().endsWith("[bot]");
    }
}
