package com.kholodilin.collector.execution;
import com.kholodilin.collector.credentials.*;
import com.kholodilin.collector.queue.JobQueue;
import javax.sql.DataSource;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"collector.execution.enabled","collector.storage.enabled","collector.credentials.enabled"},havingValue="true")
@EnableConfigurationProperties(ExecutionProperties.class)
public class CollectorExecutionConfiguration {
    @Bean LocalGitHubPacer localGitHubPacer(){return new LocalGitHubPacer();}
    @Bean ObservationStore observationStore(@Qualifier("collectorDataSource") DataSource ds,@Qualifier("collectorTransactionManager") DataSourceTransactionManager tm,ExecutionProperties properties){return new JdbcObservationStore(new JdbcTemplate(ds),new TransactionTemplate(tm),properties.maximumPayloadBytes()-512);}
    @Bean SourceReader githubSourceReader(ObservationStore store,CredentialCache cache,CollectorCredentialProperties credentials,ExecutionProperties properties,LocalGitHubPacer pacer){return new GitHubSourceReader(store,cache,credentials,properties,pacer);}
    @Bean AssignedTaskCollector assignedTaskCollector(SourceReader source,ExecutionProperties properties){return new AssignedTaskCollector(source,properties);}
    @Bean(destroyMethod="close") AssignedJobExecutor assignedJobExecutor(JobQueue queue,AssignedTaskCollector collector,ExecutionProperties properties){return new AssignedJobExecutor(queue,collector,properties);}
    @Bean SmartLifecycle collectorExecutionLoop(AssignedJobExecutor executor,ExecutionProperties properties){return new SmartLifecycle(){
        private ScheduledExecutorService loop;private volatile boolean running;
        public void start(){running=true;loop=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"collector-assigned-execution");t.setDaemon(true);return t;});loop.scheduleWithFixedDelay(()->{try{executor.poll("collector-executor");}catch(RuntimeException ignored){}},0,properties.pollInterval().toMillis(),TimeUnit.MILLISECONDS);}
        public void stop(){running=false;if(loop!=null)loop.shutdownNow();}
        public boolean isRunning(){return running;}
    };}
}
