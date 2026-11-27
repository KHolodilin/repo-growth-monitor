/**
 * GitHub REST/GraphQL <em>wire</em> DTOs used when applying collector snapshots into the product
 * database ({@code CollectorHistoryApplier}, publication bootstrap, search enricher, event
 * detectors).
 *
 * <p><b>Why here (not {@code github.model}, not collector-contracts):</b> the ТЗ has no product
 * {@code github} module. Live product traffic/stats reads use collector-contracts snapshot payloads
 * and JDBC rows, not these Jackson shapes. {@code collector-contracts} stays a separate Maven
 * module with protocol/headers only — it does not own these response records.
 *
 * <p><b>Consumers:</b> {@code collection.service} (ingestion), {@code search.service} (search item
 * enrich), {@code events.detect} (activity snapshots), {@code administration.service} (publication
 * remote repo shape).
 *
 * <p><b>Currently unused by product code</b> (kept as documented wire shapes for collector JSON
 * that may still appear in snapshots): {@code GitHubTrafficViewsResponse}, {@code
 * GitHubTrafficClonesResponse}, {@code GitHubTrafficDay}, {@code GitHubReferrerResponse}, {@code
 * GitHubPathResponse}, {@code GitHubSidebarResponse}, {@code GitHubSocialPreview}, {@code
 * GitHubSocialPreviewResponse}, {@code GitHubReleaseResponse}, {@code GitHubSearchResponse}.
 */
package com.kholodilin.repogrowth.collection.integration.github;
