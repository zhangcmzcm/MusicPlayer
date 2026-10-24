package org.example.music.storage.config;

import java.net.URI;
import org.example.music.storage.S3AudioStorage;
import org.example.music.storage.domain.AudioStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                properties.accessKey(),
                properties.secretKey()
        );

        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(properties.connectTimeout())
                        .apiCallTimeout(properties.readTimeout())
                        .build())
                .build();
    }

    @Bean
    AudioStorage audioStorage(S3Client s3Client) {
        return new S3AudioStorage(s3Client);
    }
}
