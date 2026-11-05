package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.example.music.user.domain.*;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.service.PasswordResetSnapshotReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
@SpringBootTest @ActiveProfiles("test") @Testcontainers @EnabledIfDockerAvailable
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetSnapshotIntegrationTest {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
 @DynamicPropertySource static void deps(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.data.redis.username",()->"default");r.add("spring.data.redis.password",()->"");r.add("spring.data.redis.database",()->"0");
 }
 @Autowired UserRepository users; @Autowired PasswordResetSnapshotReader reader;@Autowired JdbcTemplate jdbc;
 @BeforeEach void clear(){users.deleteAll();}
 @ParameterizedTest @ValueSource(strings={" alice@example.com","alice@example.com ","álice@example.com"})
 void should_not_match_padded_or_accent_only_historical_address(String stored){
  users.saveAndFlush(new UserEntity("u"+UUID.randomUUID().toString().substring(0,8),stored,"not-used-hash",UserRole.USER));
  assertTrue(reader.capture("alice@example.com").isEmpty());
 }
 @Test void should_capture_case_variant_and_invalidate_changed_binding_without_writing_user(){
  var user=users.saveAndFlush(new UserEntity("alice","ALICE@example.com","not-used-hash",UserRole.ADMIN));
  var before=jdbc.queryForMap("select * from users where id=?",user.getId());
  var snapshot=reader.capture("alice@example.com").orElseThrow();assertEquals("ALICE@example.com",snapshot.email());
  assertTrue(reader.stillMatches(snapshot,"alice@example.com"));assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));
  jdbc.update("update users set username='changed-name' where id=?",user.getId());assertTrue(reader.stillMatches(snapshot,"alice@example.com"));
  jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());assertFalse(reader.stillMatches(snapshot,"alice@example.com"));
 }
}
