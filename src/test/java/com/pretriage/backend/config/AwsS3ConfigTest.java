package com.pretriage.backend.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsS3ConfigTest {

    @Test
    void credentialsProviderUsesDefaultChainWhenNoStaticPairIsConfigured() {
        AwsCredentialsProvider provider = new AwsS3Config("us-east-1", "", "").credentialsProvider();

        assertInstanceOf(DefaultCredentialsProvider.class, provider);
        ((DefaultCredentialsProvider) provider).close();
    }

    @Test
    void credentialsProviderKeepsLegacyStaticPairWhenBothKeysAreConfigured() {
        AwsCredentialsProvider provider = new AwsS3Config("us-east-1", "local-access", "local-secret")
                .credentialsProvider();

        assertInstanceOf(StaticCredentialsProvider.class, provider);
        AwsCredentials credentials = provider.resolveCredentials();
        assertEquals("local-access", credentials.accessKeyId());
        assertEquals("local-secret", credentials.secretAccessKey());
    }

    @Test
    void credentialsProviderRejectsIncompleteStaticPair() {
        AwsS3Config config = new AwsS3Config("us-east-1", "local-access", " ");

        assertThrows(IllegalStateException.class, config::credentialsProvider);
    }
}
