package com.kholodilin.collector.delivery;
import com.kholodilin.collector.credentials.CollectorCredentialProperties;
import com.kholodilin.collector.queue.JobQueue;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"collector.delivery.enabled","collector.storage.enabled","collector.credentials.enabled"},havingValue="true")
@EnableConfigurationProperties(DeliveryProperties.class)
public class CollectorDeliveryConfiguration {
    @Bean ResultDeliveryWorker resultDeliveryWorker(JobQueue queue,DeliveryProperties properties,CollectorCredentialProperties credentials){return new ResultDeliveryWorker(queue,properties,credentials);}
    @Bean SmartLifecycle collectorDeliveryLoop(ResultDeliveryWorker worker,DeliveryProperties properties){return new SmartLifecycle(){
        private ScheduledExecutorService loop;private volatile boolean running;
        public void start(){running=true;loop=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"collector-result-delivery");t.setDaemon(true);return t;});loop.scheduleWithFixedDelay(()->{try{worker.poll("collector-delivery");}catch(RuntimeException ignored){}},0,properties.pollInterval().toMillis(),TimeUnit.MILLISECONDS);}
        public void stop(){running=false;if(loop!=null)loop.shutdownNow();}public boolean isRunning(){return running;}
    };}
}
