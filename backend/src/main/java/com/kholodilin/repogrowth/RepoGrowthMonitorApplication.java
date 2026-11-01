package com.kholodilin.repogrowth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.cli.UsersCli;
import com.kholodilin.repogrowth.common.config.AppProperties;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.common.config.TrafficProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties({
        AppProperties.class,
        GitHubProperties.class,
        CollectionProperties.class,
        SearchProperties.class,
        TrafficProperties.class
})
public class RepoGrowthMonitorApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "users".equals(args[0])) {
            SpringApplication app = new SpringApplication(RepoGrowthMonitorApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setAdditionalProfiles("cli");
            try (ConfigurableApplicationContext context = app.run(args)) {
                int code = new UsersCli(context.getBean(UserAccountService.class)).run(args);
                System.exit(SpringApplication.exit(context, () -> code));
            }
            return;
        }
        SpringApplication.run(RepoGrowthMonitorApplication.class, args);
    }
}
