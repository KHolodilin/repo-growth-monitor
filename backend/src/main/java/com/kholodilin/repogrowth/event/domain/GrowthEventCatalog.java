package com.kholodilin.repogrowth.event.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GrowthEventCatalog {

    public static final String SOURCE_GITHUB = "GITHUB";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    public static final String CATEGORY_DISCOVERABILITY = "DISCOVERABILITY";
    public static final String CATEGORY_COMMUNITY = "COMMUNITY";
    public static final String CATEGORY_RELEASE = "RELEASE";
    public static final String CATEGORY_MILESTONE = "MILESTONE";
    public static final String CATEGORY_PROMOTION = "PROMOTION";

    public static final String README_SIGNIFICANTLY_CHANGED = "README_SIGNIFICANTLY_CHANGED";
    public static final String DESCRIPTION_CHANGED = "DESCRIPTION_CHANGED";
    public static final String TOPICS_CHANGED = "TOPICS_CHANGED";
    public static final String HOMEPAGE_CHANGED = "HOMEPAGE_CHANGED";
    public static final String GOOD_FIRST_ISSUE_PUBLISHED = "GOOD_FIRST_ISSUE_PUBLISHED";
    public static final String EXTERNAL_ISSUE_OPENED = "EXTERNAL_ISSUE_OPENED";
    public static final String EXTERNAL_PR_OPENED = "EXTERNAL_PR_OPENED";
    public static final String EXTERNAL_PR_MERGED = "EXTERNAL_PR_MERGED";
    public static final String FIRST_EXTERNAL_CONTRIBUTOR = "FIRST_EXTERNAL_CONTRIBUTOR";
    public static final String RELEASE_PUBLISHED = "RELEASE_PUBLISHED";
    public static final String FIRST_EXTERNAL_FORK = "FIRST_EXTERNAL_FORK";
    public static final String FORK_MILESTONE = "FORK_MILESTONE";
    public static final String STAR_MILESTONE = "STAR_MILESTONE";
    public static final String CONTRIBUTOR_MILESTONE = "CONTRIBUTOR_MILESTONE";

    public static final String LINKEDIN_POST = "LINKEDIN_POST";
    public static final String REDDIT_POST = "REDDIT_POST";
    public static final String HACKER_NEWS_POST = "HACKER_NEWS_POST";
    public static final String HABR_ARTICLE = "HABR_ARTICLE";
    public static final String DEVTO_ARTICLE = "DEVTO_ARTICLE";
    public static final String MEDIUM_ARTICLE = "MEDIUM_ARTICLE";
    public static final String TELEGRAM_POST = "TELEGRAM_POST";
    public static final String YOUTUBE_VIDEO = "YOUTUBE_VIDEO";
    public static final String CUSTOM = "CUSTOM";

    public static final List<Integer> STAR_THRESHOLDS = List.of(10, 50, 100);
    public static final List<Integer> FORK_THRESHOLDS = List.of(10);
    public static final List<Integer> CONTRIBUTOR_THRESHOLDS = List.of(10);

    private static final Map<String, Boolean> AUTOMATIC_DEFAULTS = Map.ofEntries(
            Map.entry(README_SIGNIFICANTLY_CHANGED, true),
            Map.entry(DESCRIPTION_CHANGED, true),
            Map.entry(TOPICS_CHANGED, true),
            Map.entry(HOMEPAGE_CHANGED, false),
            Map.entry(GOOD_FIRST_ISSUE_PUBLISHED, true),
            Map.entry(EXTERNAL_ISSUE_OPENED, true),
            Map.entry(EXTERNAL_PR_OPENED, true),
            Map.entry(EXTERNAL_PR_MERGED, true),
            Map.entry(FIRST_EXTERNAL_CONTRIBUTOR, true),
            Map.entry(RELEASE_PUBLISHED, true),
            Map.entry(FIRST_EXTERNAL_FORK, false),
            Map.entry(FORK_MILESTONE, false),
            Map.entry(STAR_MILESTONE, false),
            Map.entry(CONTRIBUTOR_MILESTONE, false)
    );

    private static final Set<String> MANUAL_TYPES = Set.of(
            LINKEDIN_POST,
            REDDIT_POST,
            HACKER_NEWS_POST,
            HABR_ARTICLE,
            DEVTO_ARTICLE,
            MEDIUM_ARTICLE,
            TELEGRAM_POST,
            YOUTUBE_VIDEO,
            CUSTOM
    );

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry(README_SIGNIFICANTLY_CHANGED, CATEGORY_DISCOVERABILITY),
            Map.entry(DESCRIPTION_CHANGED, CATEGORY_DISCOVERABILITY),
            Map.entry(TOPICS_CHANGED, CATEGORY_DISCOVERABILITY),
            Map.entry(HOMEPAGE_CHANGED, CATEGORY_DISCOVERABILITY),
            Map.entry(GOOD_FIRST_ISSUE_PUBLISHED, CATEGORY_COMMUNITY),
            Map.entry(EXTERNAL_ISSUE_OPENED, CATEGORY_COMMUNITY),
            Map.entry(EXTERNAL_PR_OPENED, CATEGORY_COMMUNITY),
            Map.entry(EXTERNAL_PR_MERGED, CATEGORY_COMMUNITY),
            Map.entry(FIRST_EXTERNAL_CONTRIBUTOR, CATEGORY_COMMUNITY),
            Map.entry(RELEASE_PUBLISHED, CATEGORY_RELEASE),
            Map.entry(FIRST_EXTERNAL_FORK, CATEGORY_MILESTONE),
            Map.entry(FORK_MILESTONE, CATEGORY_MILESTONE),
            Map.entry(STAR_MILESTONE, CATEGORY_MILESTONE),
            Map.entry(CONTRIBUTOR_MILESTONE, CATEGORY_MILESTONE),
            Map.entry(LINKEDIN_POST, CATEGORY_PROMOTION),
            Map.entry(REDDIT_POST, CATEGORY_PROMOTION),
            Map.entry(HACKER_NEWS_POST, CATEGORY_PROMOTION),
            Map.entry(HABR_ARTICLE, CATEGORY_PROMOTION),
            Map.entry(DEVTO_ARTICLE, CATEGORY_PROMOTION),
            Map.entry(MEDIUM_ARTICLE, CATEGORY_PROMOTION),
            Map.entry(TELEGRAM_POST, CATEGORY_PROMOTION),
            Map.entry(YOUTUBE_VIDEO, CATEGORY_PROMOTION),
            Map.entry(CUSTOM, CATEGORY_PROMOTION)
    );

    private GrowthEventCatalog() {
    }

    public static Map<String, Boolean> automaticDefaults() {
        return AUTOMATIC_DEFAULTS;
    }

    public static boolean isManualType(String type) {
        return type != null && MANUAL_TYPES.contains(type);
    }

    public static boolean isAutomaticType(String type) {
        return type != null && AUTOMATIC_DEFAULTS.containsKey(type);
    }

    public static String category(String type) {
        String category = CATEGORIES.get(type);
        if (category == null) {
            throw new IllegalArgumentException("Unknown growth event type: " + type);
        }
        return category;
    }

    public static Set<String> manualTypes() {
        return MANUAL_TYPES;
    }
}
