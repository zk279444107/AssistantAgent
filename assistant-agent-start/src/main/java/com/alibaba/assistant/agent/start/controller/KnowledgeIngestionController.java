package com.alibaba.assistant.agent.start.controller;

import com.alibaba.assistant.agent.start.service.KnowledgeIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/ingest")
public class KnowledgeIngestionController {

    private final KnowledgeIngestionService ingestionService;
    private final com.alibaba.assistant.agent.extension.search.spi.SearchProvider searchProvider;

    @Autowired
    public KnowledgeIngestionController(KnowledgeIngestionService ingestionService, 
                                        @org.springframework.beans.factory.annotation.Qualifier("vectorKnowledgeSearchProvider") com.alibaba.assistant.agent.extension.search.spi.SearchProvider searchProvider) {
        this.ingestionService = ingestionService;
        this.searchProvider = searchProvider;
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("query") String query,
                                                      @RequestParam(value = "topK", defaultValue = "10") int topK) {
        
        com.alibaba.assistant.agent.extension.search.model.SearchRequest request = new com.alibaba.assistant.agent.extension.search.model.SearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        
        java.util.List<com.alibaba.assistant.agent.extension.search.model.SearchResultItem> results = searchProvider.search(request);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", results.size());
        response.put("results", results);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestParam("url") String url, 
                                                         @RequestParam(value = "title", required = false) String title,
                                                         @RequestParam(value = "keywords", required = false) java.util.List<String> keywords) {
        int count = ingestionService.ingestUrl(url, title, keywords);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Successfully ingested URL");
        response.put("url", url);
        response.put("documents_added", count);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/file")
    public ResponseEntity<Map<String, Object>> ingestFile(@RequestParam("file") MultipartFile file, 
                                                          @RequestParam(value = "title", required = false) String title,
                                                          @RequestParam(value = "keywords", required = false) java.util.List<String> keywords) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "File is empty"));
        }
        
        int count = ingestionService.ingestFile(file, title, keywords);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Successfully ingested file");
        response.put("filename", file.getOriginalFilename());
        response.put("documents_added", count);
        return ResponseEntity.ok(response);
    }
}
