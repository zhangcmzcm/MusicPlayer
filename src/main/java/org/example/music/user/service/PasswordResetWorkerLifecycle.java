package org.example.music.user.service;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.example.music.user.config.PasswordResetProperties;
import org.example.music.user.exception.PasswordResetDiagnostics;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
@Component
public class PasswordResetWorkerLifecycle implements SmartLifecycle {
 private static final Logger LOG=LoggerFactory.getLogger(PasswordResetWorkerLifecycle.class);
 private final PasswordResetTaskProcessor processor;private final PasswordResetRequestStore store;private final PasswordResetProperties properties;
 private ScheduledThreadPoolExecutor workers,maintenance;private volatile boolean running;
 public PasswordResetWorkerLifecycle(PasswordResetTaskProcessor processor,PasswordResetRequestStore store,PasswordResetProperties properties){this.processor=processor;this.store=store;this.properties=properties;}
 @Override public synchronized void start(){
  if(running)return;
  workers=new ScheduledThreadPoolExecutor(properties.getWorkerConcurrency(),threads("password-reset-worker-"));
  maintenance=new ScheduledThreadPoolExecutor(1,threads("password-reset-maintenance-"));
  workers.setRemoveOnCancelPolicy(true);maintenance.setRemoveOnCancelPolicy(true);running=true;
  for(int i=0;i<properties.getWorkerConcurrency();i++)workers.scheduleWithFixedDelay(()->guard(()->processor.processNext()),0,250,TimeUnit.MILLISECONDS);
  maintenance.scheduleWithFixedDelay(()->guard(()->store.reap(100)),0,1,TimeUnit.SECONDS);
 }
 private void guard(Runnable action){if(!running)return;try{action.run();}catch(RuntimeException failure){LOG.warn("Password reset background iteration failed, exceptionType={}",failure.getClass().getSimpleName(),PasswordResetDiagnostics.sanitized(failure));}}
 @Override @PreDestroy public synchronized void stop(){
  if(!running)return;running=false;workers.shutdownNow();maintenance.shutdownNow();
  await(workers);await(maintenance);
 }
 private void await(ExecutorService executor){try{if(!executor.awaitTermination(1,TimeUnit.SECONDS))LOG.warn("Password reset background shutdown still has in-flight work");}catch(InterruptedException failure){Thread.currentThread().interrupt();}}
 @Override public boolean isRunning(){return running;}
 private static ThreadFactory threads(String prefix){var ids=new AtomicInteger();return action->{var thread=new Thread(action,prefix+ids.incrementAndGet());thread.setDaemon(true);return thread;};}
}
