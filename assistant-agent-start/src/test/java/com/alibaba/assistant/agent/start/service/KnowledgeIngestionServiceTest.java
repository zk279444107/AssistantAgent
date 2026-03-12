package com.alibaba.assistant.agent.start.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeIngestionServiceTest {

    private VectorStore vectorStore;
    private KnowledgeIngestionService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        service = new KnowledgeIngestionService(vectorStore);
    }

    @Test
    void testIngestFile() throws IOException {
        // Create a mock file
        String content = "This is a test document content for ingestion.";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );

        // Execute
        int count = service.ingestFile(file, null, null);

        // Verify
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        
        List<Document> documents = captor.getValue();
        // Depending on TokenTextSplitter, it might be 1 or more. 
        // For short content, it should be 1.
        assertEquals(1, documents.size());
        
        // Tika might add newlines or metadata, but the core text should be present
        // Note: TikaDocumentReader with text/plain might behave simply
        // Let's just check if it called vectorStore.add
    }
}
