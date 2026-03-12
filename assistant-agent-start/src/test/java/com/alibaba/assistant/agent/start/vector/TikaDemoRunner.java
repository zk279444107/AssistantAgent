package com.alibaba.assistant.agent.start.vector;

import com.alibaba.assistant.agent.extension.search.model.SearchRequest;
import com.alibaba.assistant.agent.extension.search.model.SearchResultItem;
import com.alibaba.assistant.agent.start.search.VectorKnowledgeSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.List;

@Configuration
public class TikaDemoRunner {

    private static final Logger logger = LoggerFactory.getLogger(TikaDemoRunner.class);

    @Bean
    public CommandLineRunner runTikaDemo(TikaVectorizationDemo tikaVectorizationDemo, VectorKnowledgeSearchProvider searchProvider) {
        return args -> {
            logger.info("Tika Vectorization Demo Started");

            try {
                // Process the demo data
                Resource resource = new ClassPathResource("demo-data.txt");
                if (resource.exists()) {
                    tikaVectorizationDemo.processDocument(resource);

                    // Search for relevant content using TikaVectorizationDemo
                    logger.info("Searching for 'Spring AI' using TikaVectorizationDemo...");
                    List<Document> results = tikaVectorizationDemo.search("Spring AI");
                    
                    logger.info("Tika Search Results: {}", results.size());
                    for (Document doc : results) {
                        String text = doc.getText();
                        logger.info("- {}", text.substring(0, Math.min(text.length(), 100)) + "...");
                    }

                    // Test SearchProvider
                    logger.info("Testing VectorKnowledgeSearchProvider...");
                    SearchRequest request = new SearchRequest();
                    request.setQuery("Spring AI");
                    request.setTopK(3);
                    List<SearchResultItem> items = searchProvider.search(request);
                    logger.info("SearchProvider Results: {}", items.size());
                    for (SearchResultItem item : items) {
                        logger.info("- Title: {}, Score: {}, Snippet: {}", item.getTitle(), item.getScore(), item.getSnippet());
                    }

                } else {
                    logger.warn("Demo data file not found: demo-data.txt");
                }
            } catch (Exception e) {
                logger.error("Error running Tika Vectorization Demo", e);
            }

            logger.info("Tika Vectorization Demo Finished");
        };
    }
}
