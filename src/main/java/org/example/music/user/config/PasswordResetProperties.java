package org.example.music.user.config;
import java.time.Duration;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
@Component @Validated @ConfigurationProperties("app.password-reset")
public class PasswordResetProperties {
 @Min(1) private int queueCapacity=1000;
 @Min(1) private int workerConcurrency=4;
 @NotNull private Duration sendTimeout=Duration.ofSeconds(20);
 public int getQueueCapacity(){return queueCapacity;}
 public void setQueueCapacity(int value){queueCapacity=value;}
 public int getWorkerConcurrency(){return workerConcurrency;}
 public void setWorkerConcurrency(int value){workerConcurrency=value;}
 public Duration getSendTimeout(){return sendTimeout;}
 public void setSendTimeout(Duration value){sendTimeout=value;}
 @AssertTrue(message="发送超时必须是可表示的正毫秒值")
 public boolean isSendTimeoutValid(){try{return sendTimeout!=null&&sendTimeout.toMillis()>0&&sendTimeout.toMillis()<=Integer.MAX_VALUE;}catch(ArithmeticException failure){return false;}}
}
