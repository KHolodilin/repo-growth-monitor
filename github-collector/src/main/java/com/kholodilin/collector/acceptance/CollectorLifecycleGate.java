package com.kholodilin.collector.acceptance;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Stops accepting new jobs on shutdown while in-flight work drains. */
@Slf4j
@Component
@ConditionalOnProperty(name = "collector.runtime.enabled", havingValue = "true")
public class CollectorLifecycleGate implements SmartLifecycle {
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private volatile boolean running;

  public boolean accepting() {
    return accepting.get();
  }

  @Override
  public void start() {
    accepting.set(true);
    running = true;
    log.info("Collector accept gate open");
  }

  @Override
  public void stop() {
    accepting.set(false);
    running = false;
    log.info("Collector shutdown: accept gate closed");
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Run before execution/delivery loops so accept closes first. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
