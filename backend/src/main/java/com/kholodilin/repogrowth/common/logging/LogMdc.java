package com.kholodilin.repogrowth.common.logging;

import org.slf4j.MDC;

public final class LogMdc {

    private LogMdc() {
    }

    public static void repositoryId(Long id) {
        put("repositoryId", id);
    }

    public static void collectionRunId(Long id) {
        put("collectionRunId", id);
    }

    public static void collectionJobId(Long id) {
        put("collectionJobId", id);
    }

    public static void searchRunId(Long id) {
        put("searchRunId", id);
    }

    public static void clearJob() {
        MDC.remove("repositoryId");
        MDC.remove("collectionRunId");
        MDC.remove("collectionJobId");
        MDC.remove("searchRunId");
    }

    private static void put(String key, Long id) {
        if (id == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, String.valueOf(id));
        }
    }
}
