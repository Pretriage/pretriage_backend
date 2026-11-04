package com.pretriage.backend.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;


@Configuration
@ConditionalOnProperty(name = "pretriage.storage.s3.enabled", havingValue = "true")
public class AwsS3Config {
    private final String region;
    private final String accessKey;
    private final String secretKey;

    public AwsS3Config(@Value("${aws.s3.region}") String region,
                       @Value("${aws.accessKeyId:}") String accessKey,
                       @Value("${aws.secretAccessKey:}") String secretKey) {
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /**
     * Configura el cliente S3 para interactuar con AWS.
     * @return Instancia de S3Client
     */
    @Bean
    public S3Client amazonS3Client() {
        AwsCredentialsProvider credentialsProvider = credentialsProvider();
        return S3Client.builder()
                .region(Region.of(this.region))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    AwsCredentialsProvider credentialsProvider() {
        boolean hasAccessKey = accessKey != null && !accessKey.isBlank();
        boolean hasSecretKey = secretKey != null && !secretKey.isBlank();
        if (hasAccessKey != hasSecretKey) {
            throw new IllegalStateException(
                    "Configure both AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, or configure neither to use the AWS role/default credential chain");
        }
        if (hasAccessKey) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
