package com.alibaba.assistant.agent.start.search;

import com.alibaba.assistant.agent.extension.search.model.SearchRequest;
import com.alibaba.assistant.agent.extension.search.model.SearchResultItem;
import com.alibaba.assistant.agent.extension.search.model.SearchSourceType;
import com.alibaba.assistant.agent.extension.search.spi.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 向量知识库搜索Provider
 * 使用 VectorStore 进行语义搜索
 *
 * @author Assistant Agent Team
 */
@Component
public class VectorKnowledgeSearchProvider implements SearchProvider {

    private static final Logger logger = LoggerFactory.getLogger(VectorKnowledgeSearchProvider.class);
    
    // 默认相似度阈值，过滤掉相关度较低的结果
    // 降低阈值以增加召回，依靠后续重排序提升精度
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;
    
    // 标题匹配提升权重
    private static final double TITLE_MATCH_BOOST = 0.2;
    // 关键词匹配提升权重
    private static final double KEYWORD_MATCH_BOOST = 0.3;
    
    // 摘要最大长度
    private static final int MAX_SNIPPET_LENGTH = 200;

    private final VectorStore vectorStore;

    @Autowired
    public VectorKnowledgeSearchProvider(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public boolean supports(SearchSourceType type) {
        return SearchSourceType.KNOWLEDGE == type;
    }

    @Override
    public List<SearchResultItem> search(SearchRequest request) {
        logger.info("VectorKnowledgeSearchProvider#search - reason=execute vector search with query={}, topK={}", 
                request.getQuery(), request.getTopK());
        
        try {
            int originalTopK = request.getTopK() > 0 ? request.getTopK() : 10;
            // 扩大检索范围，为重排序提供更多候选
            // int candidateTopK = originalTopK * 3;

            // 使用 Spring AI 的 SearchRequest 构建查询
            // 设置 similarityThreshold 避免返回过多无关结果
            org.springframework.ai.vectorstore.SearchRequest vectorSearchRequest = 
                 org.springframework.ai.vectorstore.SearchRequest.builder()
                     .query(request.getQuery())
                     .topK(originalTopK)
                     .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                     .build();
            
            List<Document> documents = vectorStore.similaritySearch(vectorSearchRequest);
            
            if (documents.isEmpty()) {
                logger.debug("VectorKnowledgeSearchProvider#search - result=no documents found");
                return Collections.emptyList();
            }

            // 转换为 ResultItem
            List<SearchResultItem> items = documents.stream()
                    .map(this::toSearchResultItem)
                    .collect(Collectors.toList());
            
            // 执行重排序逻辑
            return reRankResults(items, request.getQuery(), originalTopK);

        } catch (Exception e) {
            logger.error("VectorKnowledgeSearchProvider#search - reason=vector search failed, error={}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 重排序逻辑：根据标题和关键词匹配度提升分数
     */
    private List<SearchResultItem> reRankResults(List<SearchResultItem> items, String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return items.stream().limit(topK).collect(Collectors.toList());
        }
        
        // 简单的分词，按空格分割
        String[] queryTerms = query.toLowerCase().split("\\s+");
        
        for (SearchResultItem item : items) {
            double boost = 0.0;
            
            // 1. 标题匹配提升
            String title = item.getTitle();
            if (StringUtils.hasText(title)) {
                String lowerTitle = title.toLowerCase();
                for (String term : queryTerms) {
                    if (lowerTitle.contains(term)) {
                        boost += TITLE_MATCH_BOOST;
                        // 只要有一个词匹配就加分，避免重复加分
                        break; 
                    }
                }
            }
            
            // 2. 关键词元数据匹配提升
            Map<String, Object> metadata = item.getMetadata().getExtensions();
            if (metadata != null && metadata.containsKey("matched_keywords")) {
                 Object keywordsObj = metadata.get("matched_keywords");
                 if (keywordsObj instanceof List) {
                     List<?> list = (List<?>) keywordsObj;
                     boolean keywordMatched = false;
                     for (Object obj : list) {
                         String k = String.valueOf(obj).toLowerCase();
                         for (String term : queryTerms) {
                             if (k.contains(term)) {
                                 keywordMatched = true;
                                 break; 
                             }
                         }
                         if (keywordMatched) break;
                     }
                     if (keywordMatched) {
                         boost += KEYWORD_MATCH_BOOST;
                     }
                 }
            }
            
            // 应用加分
            if (boost > 0) {
                item.setScore(item.getScore() + boost);
            }
        }
        
        // 按分数降序排序并截取 topK
        return items.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 将 Document 转换为 SearchResultItem
     */
    private SearchResultItem toSearchResultItem(Document doc) {
        SearchResultItem item = new SearchResultItem();
        // 如果文档没有 ID，生成一个
        item.setId(doc.getId() != null ? doc.getId() : UUID.randomUUID().toString());
        item.setSourceType(SearchSourceType.KNOWLEDGE);
        
        // 尝试从元数据中获取标题和分数
        Map<String, Object> metadata = doc.getMetadata();
        String title = "Untitled Document";
        
        if (metadata != null) {
            if (metadata.containsKey("title")) {
                title = String.valueOf(metadata.get("title"));
            } else if (metadata.containsKey("source_filename")) {
                title = String.valueOf(metadata.get("source_filename"));
            }
            
            // 尝试提取相似度分数/距离
            extractScore(item, metadata);
            
            // 复制元数据到扩展字段
            if (item.getMetadata().getExtensions() == null) {
                item.getMetadata().setExtensions(new HashMap<>());
            }
            item.getMetadata().getExtensions().putAll(metadata);
        }
        item.setTitle(title);
        
        // 设置内容
        String content = doc.getText();
        item.setContent(content);
        
        // 生成摘要
        if (StringUtils.hasText(content)) {
            item.setSnippet(content.length() > MAX_SNIPPET_LENGTH ? 
                    content.substring(0, MAX_SNIPPET_LENGTH) + "..." : content);
        } else {
            item.setSnippet("");
        }
        
        item.getMetadata().setSourceName(getName());
        
        return item;
    }
    
    private void extractScore(SearchResultItem item, Map<String, Object> metadata) {
        if (metadata.containsKey("distance")) {
            Object distanceObj = metadata.get("distance");
            if (distanceObj instanceof Number) {
                // 假设是余弦距离，相似度 = 1 - 距离
                // 具体取决于 VectorStore 实现，这里做通用假设
                item.setScore(1.0 - ((Number) distanceObj).doubleValue());
            }
        } else if (metadata.containsKey("score")) {
             Object scoreObj = metadata.get("score");
             if (scoreObj instanceof Number) {
                 item.setScore(((Number) scoreObj).doubleValue());
             }
        }
    }

    @Override
    public String getName() {
        return "VectorKnowledgeSearchProvider";
    }
}
