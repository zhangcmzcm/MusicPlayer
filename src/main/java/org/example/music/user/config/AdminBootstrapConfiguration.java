package org.example.music.user.config;

import org.example.music.security.PasswordHasher;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleChangeGuardRepository;
import org.example.music.user.service.AdminBootstrapInitializer;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapConfiguration {

    @Bean
    public ApplicationRunner adminBootstrapInitializer(
            AdminBootstrapProperties properties,
            PasswordHasher passwordHasher,
            UserRepository userRepository,
            UserRoleChangeGuardRepository userRoleChangeGuardRepository,
            PlatformTransactionManager transactionManager
    ) {
        return new AdminBootstrapInitializer(
                properties,
                passwordHasher,
                userRepository,
                userRoleChangeGuardRepository,
                new TransactionTemplate(transactionManager)
        );
    }
}
