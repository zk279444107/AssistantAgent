package com.alibaba.assistant.agent.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaApi.Tenant;
import org.springframework.ai.chroma.vectorstore.ChromaApi.Database;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class ChromaConfig {

    private static final Logger logger = LoggerFactory.getLogger(ChromaConfig.class);

    @Value("${spring.ai.vectorstore.chroma.client.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.chroma.client.port:8000}")
    private int port;

    @Value("${spring.ai.vectorstore.chroma.initialize-schema:true}")
    private boolean initializeSchema;

    @Value("${spring.ai.vectorstore.chroma.collection-name:SpringAiCollection}")
    private String collectionName;

    @Bean
    public ChromaApi chromaApi(ObjectMapper objectMapper) {
        // http://localhost:8000
        String chromaUrl = "http://" + host + ":" + port;
        return new ChromaApi(chromaUrl, RestClient.builder(), objectMapper);
    }

    @Bean
    public ChromaVectorStore vectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel) {
        if (initializeSchema) {
            try {
                // Explicitly provide default tenant and database names required by ChromaApi
                String tenantName = "SpringAiTenant";
                String databaseName = "SpringAiDatabase";

                // 1. Initialize Tenant
                try {
                    Tenant tenant = chromaApi.getTenant(tenantName);
                    logger.info("ChromaConfig#vectorStore - reason=Tenant '{}' already exists, skipping creation...",
                            tenant);
                    if (tenant == null) {
                        chromaApi.createTenant(tenantName);
                    }
                } catch (Exception e) {
                    logger.info("ChromaConfig#vectorStore - reason=Tenant '{}' not found, creating...", tenantName);
                    throw e;
                }

                // 2. Initialize Database
                try {
                    Database database = chromaApi.getDatabase(tenantName, databaseName);
                    logger.info(
                            "ChromaConfig#vectorStore - reason=Database '{}' already exists in tenant '{}', skipping creation...",
                            database, tenantName);
                    if (database == null) {
                        chromaApi.createDatabase(tenantName, databaseName);
                    }
                } catch (Exception e) {
                    logger.info("ChromaConfig#vectorStore - reason=Database '{}' not found in tenant '{}', creating...",
                            databaseName, tenantName);
                    throw e;
                }

                // 3. Initialize Collection
                // Check if collection exists to avoid unnecessary creation attempts
                // Note: getCollection throws exception if not found in some versions, or
                // returns null/empty
                boolean exists = false;
                try {
                    var collection = chromaApi.getCollection(tenantName, databaseName, collectionName);
                    if (collection != null) {
                        exists = true;
                    }
                } catch (Exception e) {
                    // Collection likely doesn't exist
                }

                if (!exists) {
                    chromaApi.createCollection(tenantName, databaseName,
                            new ChromaApi.CreateCollectionRequest(collectionName));
                    logger.info("ChromaConfig#vectorStore - reason=Successfully created collection, collectionName={}",
                            collectionName);
                } else {
                    logger.info(
                            "ChromaConfig#vectorStore - reason=Collection already exists, skipping creation, collectionName={}",
                            collectionName);
                }
            } catch (Exception e) {
                logger.error("ChromaConfig#vectorStore - reason=Failed to check/create collection, collectionName={}",
                        collectionName, e);
                throw e;
            }
        }
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)
                .build();
    }
}
