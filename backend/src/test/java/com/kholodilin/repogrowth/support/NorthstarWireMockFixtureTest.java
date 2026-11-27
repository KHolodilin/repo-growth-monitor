package com.kholodilin.repogrowth.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the deterministic northstar mock owner fixture used by docker-compose.mock.yml /
 * WireMock regression (onboarding, collect, chart period switch).
 */
class NorthstarWireMockFixtureTest {

  private static final Path FIXTURE_ROOT =
      Path.of("").toAbsolutePath().getParent().resolve("wiremock");

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void northstarManifestDescribesTenReposAndThirtyTrafficDays() throws Exception {
    Path manifestPath = locate("northstar-fixture-manifest.json");
    JsonNode manifest = json.readTree(Files.readString(manifestPath));
    assertThat(manifest.get("owner").stringValue()).isEqualTo("northstar");
    assertThat(manifest.get("repositoryCount").intValue()).isEqualTo(10);
    assertThat(manifest.get("trafficDays").intValue()).isEqualTo(30);
    assertThat(manifest.get("repositories")).hasSize(10);
    assertThat(manifest.get("scenarios").toString())
        .contains("onboarding")
        .contains("collect")
        .contains("chart-period-switch");
  }

  @Test
  void northstarTrafficMappingsExposeThirtyDailyPoints() throws Exception {
    JsonNode views = json.readTree(Files.readString(locate("mappings/northstar-traffic-views.json")));
    assertThat(views.get("mappings")).hasSize(10);
    JsonNode firstViews = views.get("mappings").get(0).at("/response/jsonBody/views");
    assertThat(firstViews).hasSize(30);
    JsonNode clones =
        json.readTree(Files.readString(locate("mappings/northstar-traffic-clones.json")));
    assertThat(clones.get("mappings").get(0).at("/response/jsonBody/clones")).hasSize(30);
  }

  @Test
  void userReposIncludesNorthstarOwner() throws Exception {
    JsonNode userRepos = json.readTree(Files.readString(locate("mappings/user-repos.json")));
    JsonNode body = userRepos.at("/response/jsonBody");
    int northstar = 0;
    for (JsonNode node : body) {
      if ("northstar".equals(node.at("/owner/login").stringValue())) {
        northstar++;
      }
    }
    assertThat(northstar).isEqualTo(10);
  }

  private Path locate(String relative) {
    Path candidate = FIXTURE_ROOT.resolve(relative);
    if (Files.exists(candidate)) {
      return candidate;
    }
    // Surefire cwd is backend/; also accept repo-root relative from module.
    Path fromModule = Path.of("..", "wiremock").resolve(relative).normalize().toAbsolutePath();
    assertThat(fromModule).exists();
    return fromModule;
  }
}
