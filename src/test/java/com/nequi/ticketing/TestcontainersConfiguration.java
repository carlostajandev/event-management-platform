package com.nequi.ticketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;
import java.time.Duration;

/**
 * Configuración para usar los contenedores de Docker Compose en las pruebas de integración
 *
 * Requiere que los contenedores estén corriendo con:
 * docker-compose up -d dynamodb-local localstack
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TestcontainersConfiguration.class);

    // Usar los contenedores de docker-compose que ya están corriendo
    private static final String DYNAMODB_ENDPOINT = "http://localhost:8000";
    private static final String SQS_ENDPOINT = "http://localhost:4566";
    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "fakeMyKeyId";  // Mismo que en docker-compose
    private static final String SECRET_KEY = "fakeSecretAccessKey";  // Mismo que en docker-compose

    static {
        log.info("==========================================");
        log.info("🔌 Conectando a contenedores Docker Compose:");
        log.info("   └─ DynamoDB: {}", DYNAMODB_ENDPOINT);
        log.info("   └─ SQS: {}", SQS_ENDPOINT);
        log.info("==========================================");

        // Verificar que los contenedores estén accesibles
        verifyDynamoDBConnection();
        verifySQSConnection();
    }

    private static void verifyDynamoDBConnection() {
        try {
            java.net.URL dynamoUrl = new java.net.URL(DYNAMODB_ENDPOINT);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) dynamoUrl.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2000);
            conn.connect();
            log.info("✅ DynamoDB conectado exitosamente");
        } catch (Exception e) {
            log.warn("⚠️  No se pudo conectar a DynamoDB en {}", DYNAMODB_ENDPOINT);
            log.warn("   Asegúrate de ejecutar: docker-compose up -d dynamodb-local");
        }
    }

    private static void verifySQSConnection() {
        try {
            java.net.URL sqsUrl = new java.net.URL(SQS_ENDPOINT);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) sqsUrl.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2000);
            conn.connect();
            log.info("✅ SQS conectado exitosamente");
        } catch (Exception e) {
            log.warn("⚠️  No se pudo conectar a SQS en {}", SQS_ENDPOINT);
            log.warn("   Asegúrate de ejecutar: docker-compose up -d localstack");
        }
    }

    private static StaticCredentialsProvider testCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
    }

    @Bean
    @Primary
    public DynamoDbAsyncClient dynamoDbAsyncClient() {
        return DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create(DYNAMODB_ENDPOINT))
                .credentialsProvider(testCredentials())
                .region(Region.of(REGION))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(30))
                        .maxConcurrency(100))
                .build();
    }

    @Bean
    @Primary
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(
            DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .build();
    }

    @Bean
    @Primary
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(DYNAMODB_ENDPOINT))
                .credentialsProvider(testCredentials())
                .region(Region.of(REGION))
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }

    @Bean
    @Primary
    public SqsAsyncClient sqsAsyncClient() {
        return SqsAsyncClient.builder()
                .endpointOverride(URI.create(SQS_ENDPOINT))
                .credentialsProvider(testCredentials())
                .region(Region.of(REGION))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(30)))
                .build();
    }
}