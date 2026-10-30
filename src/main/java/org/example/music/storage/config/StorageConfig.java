package org.example.music.storage.config;

import java.net.URI;
import org.example.music.storage.S3AudioStorage;
import org.example.music.storage.domain.AudioStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.example.music.storage.S3AudioStreamStorage;
import org.example.music.storage.domain.AudioStreamStorage;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.core.retry.RetryPolicy;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@EnableConfigurationProperties({StorageProperties.class, StreamReadProperties.class})
public class StorageConfig {

    @Bean
    @Primary
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

    @Bean(destroyMethod="close")
    S3Client streamReadS3Client(StorageProperties properties, StreamReadProperties stream) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint())).region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(),properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(properties.connectTimeout())
                        .connectionAcquisitionTimeout(properties.connectTimeout()).socketTimeout(properties.readTimeout())
                        .maxConnections(stream.maxConnections()))
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.none()).build())
                .build();
    }

    @Bean
    AudioStreamStorage audioStreamStorage(@Qualifier("streamReadS3Client") S3Client client) {
        return new S3AudioStreamStorage(client);
    }
}
