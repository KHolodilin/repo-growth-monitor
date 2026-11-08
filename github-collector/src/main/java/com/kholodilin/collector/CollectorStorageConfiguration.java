package com.kholodilin.collector;

import com.kholodilin.collector.queue.*;
import com.zaxxer.hikari.*;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All infrastructure beans are non-default candidates: RGM auto-configuration remains independent. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="collector.storage.enabled",havingValue="true")
public class CollectorStorageConfiguration {
    @Bean(name="collectorDataSource",destroyMethod="close",defaultCandidate=false)
    HikariDataSource collectorDataSource(Environment env) {
        String url=env.getRequiredProperty("collector.datasource.url");
        if(url.equals(env.getProperty("spring.datasource.url"))) throw new IllegalArgumentException("Collector requires a separate database");
        HikariConfig config=new HikariConfig(); config.setJdbcUrl(url);
        config.setUsername(env.getRequiredProperty("collector.datasource.username"));
        config.setPassword(env.getRequiredProperty("collector.datasource.password"));
        config.setMaximumPoolSize(env.getProperty("collector.datasource.maximum-pool-size",Integer.class,5));
        config.setPoolName("collector"); return new HikariDataSource(config);
    }
    @Bean(name="collectorLiquibase",defaultCandidate=false)
    SpringLiquibase collectorLiquibase(@Qualifier("collectorDataSource") DataSource ds) {
        SpringLiquibase lb=new SpringLiquibase(); lb.setDataSource(ds);
        lb.setChangeLog("classpath:collector/db/changelog/master.yaml"); return lb;
    }
    @Bean(name="collectorTransactionManager",defaultCandidate=false)
    DataSourceTransactionManager collectorTransactionManager(@Qualifier("collectorDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
    @Bean @DependsOn("collectorLiquibase")
    HistoryPartitions collectorHistoryPartitions(@Qualifier("collectorDataSource") DataSource ds) {
        HistoryPartitions partitions=new HistoryPartitions(new JdbcTemplate(ds)); partitions.ensureCurrentAndNext(); return partitions;
    }
    @Bean
    JobQueue collectorJobQueue(@Qualifier("collectorDataSource") DataSource ds,
        @Qualifier("collectorTransactionManager") DataSourceTransactionManager tm, HistoryPartitions partitions) {
        return new PostgresJobQueue(new JdbcTemplate(ds),new TransactionTemplate(tm),partitions);
    }
}
