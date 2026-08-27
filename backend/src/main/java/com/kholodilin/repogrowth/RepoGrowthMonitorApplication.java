package com.kholodilin.repogrowth;

import com.kholodilin.repogrowth.common.config.AppProperties;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AppProperties.class,
        GitHubProperties.class,
        CollectionProperties.class,
        SearchProperties.class
})
public class RepoGrowthMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoGrowthMonitorApplication.class, args);
    }
}
