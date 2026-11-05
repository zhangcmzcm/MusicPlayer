package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.example.music.user.domain.PasswordResetIdentifiers;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
abstract class PasswordResetRedisIntegrationSupport {
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
 static LettuceConnectionFactory connection;static StringRedisTemplate redis;static PasswordResetRequestStore store;
 static final String PREFIX="music:auth:password-reset:";
 @BeforeAll static void connect(){connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);store=new PasswordResetRequestStore(redis);}
 @AfterAll static void close(){if(connection!=null)connection.destroy();}
 @BeforeEach void clean(){var keys=redis.keys(PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
 static String digest(String s){return PasswordResetIdentifiers.digest(s);}
}
