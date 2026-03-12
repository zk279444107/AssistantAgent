/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.start.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tika 向量化演示服务
 * <p>
 * 该服务演示了如何进行完整的 RAG 流程：
 * 1. 使用 Apache Tika 读取文档（通过 Spring AI TikaDocumentReader）
 * 3. 使用 TokenTextSplitter 将文档切分为片段
 * 4. 调用 VectorStore.accept()，其内部会自动使用 EmbeddingModel 将片段转换为向量并存储
 * <p>
 * 注意：
 * 本示例中没有显式依赖 EmbeddingModel，因为 Spring AI 的 VectorStore 实现（如 ChromaVectorStore）
 * 已经在配置时注入了 EmbeddingModel（见 ChromaConfig.java）。
 * 当调用 vectorStore.accept() 时，它会内部调用 embeddingModel.embed() 生成向量。
 * <p>
 * 改进特性：
 * - 噪声清洗：去除多余空白、页码、控制字符、乱码、页眉页脚等
 * - 元数据增强：保留并优化元数据
 * - 批量处理：分批写入向量库
 * - 异步处理：支持异步执行
 *
 * @author Assistant Agent Team
 */
@Service
public class TikaVectorizationDemo {

    private static final Logger logger = LoggerFactory.getLogger(TikaVectorizationDemo.class);

    // 噪声清洗正则：匹配多余的空白字符（用于段落内，保留段落间空行）
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[ \\t\\x0B\\f]+");
    // 噪声清洗正则：匹配连续的换行符（3个及以上换行替换为2个，保留段落结构）
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");

    // 噪声清洗正则：匹配可能的页码（如单独的数字，或 "Page 1 of 10" 格式）
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^\\s*(\\d+|Page\\s+\\d+\\s+of\\s+\\d+)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    // 噪声清洗正则：匹配控制字符（除了换行和制表符）
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");  
    // 噪声清洗正则：匹配连续的重复分隔符（如 "---", "___", "..."）
    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("[-=_*.]{3,}");
    // 噪声清洗正则：匹配 CID 字体映射问题 (如 (cid:123))
    private static final Pattern CID_PATTERN = Pattern.compile("\\(cid:\\d+\\)");
    // 噪声清洗正则：匹配常见的页眉页脚关键词行
    private static final Pattern COMMON_HEADER_FOOTER = Pattern.compile("^\\s*(Copyright|All rights reserved|Confidential|Draft|Internal Use Only).*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    
    // [可选] 隐私清洗：简单的邮箱和手机号脱敏（根据业务需求开启）
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}"); // 简单示例：中国手机号

    private final VectorStore vectorStore;

    /**
     * 在实际应用中，你应该使用持久化的 VectorStore，如 Chroma, Milvus, PGVector 等。
     * Spring AI 会自动配置 VectorStore Bean。
     */
    @Autowired
    public TikaVectorizationDemo(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 异步处理文档（推荐用于生产环境）
     * 使用 CompletableFuture 模拟异步处理，实际生产中建议使用消息队列（Kafka/RabbitMQ）
     *
     * @param resource 文档资源
     * @return CompletableFuture
     */
    public CompletableFuture<Void> processDocumentAsync(Resource resource) {
        return CompletableFuture.runAsync(() -> {
            try {
                processDocument(resource);
            } catch (Exception e) {
                logger.error("异步处理文档失败: {}", resource.getFilename(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 处理文档：读取 -> 清洗 -> 切分 -> 向量化 -> 存储
     *
     * @param resource 文档资源（PDF, Word 等）
     */
    public void processDocument(Resource resource) {
        logger.info("开始处理资源: {}", resource.getFilename());

        // 1. 使用 Tika 提取文本
        // TikaDocumentReader 会自动检测文件类型并提取文本
        DocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();
        logger.info("从 Tika 提取了 {} 个文档", documents.size());

        // [新增] 预处理：噪声清洗与元数据增强
        List<Document> processedDocs = new ArrayList<>();
        for (Document doc : documents) {
            // 清洗文本
            String content = doc.getText();
            String cleanContent = cleanText(content);
            
            // 增强元数据
            // 注意：不同的 VectorStore 对元数据支持程度不同，这里演示最佳实践
            // 确保保留 Tika 提取的元数据，并补充自定义元数据
            // 这里我们直接使用 doc.getMetadata() 并添加额外信息
            // 注意：Document 对象通常是不可变的或建议视为不可变，因此创建新对象
            Document processedDoc = new Document(cleanContent, doc.getMetadata());
            // 示例：添加处理时间戳
            processedDoc.getMetadata().put("processed_at", System.currentTimeMillis());
            processedDoc.getMetadata().put("source_filename", resource.getFilename());
            
            processedDocs.add(processedDoc);
        }

        // 2. 切分为片段
        // 大文档需要被切分为较小的片段以便进行向量化
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(processedDocs);
        logger.info("切分为 {} 个片段", chunks.size());

        // 3. 批量向量化并存储
        // 性能优化：分批处理，避免一次性发送过大数据包
        // 注意：vectorStore.accept() 会自动调用配置的 EmbeddingModel 进行向量化处理
        int batchSize = 64;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<Document> batch = chunks.subList(i, end);
            vectorStore.accept(batch);
            logger.info("已存储批次 [{}-{}], 共 {} 个片段", i, end, chunks.size());
        }
        
        logger.info("向量已全部存储到 VectorStore");
    }

    /**
     * 文本清洗：去除噪声
     */
    private String cleanText(String content) {
        if (content == null) {
            return "";
        }
        
        // 0. Unicode 标准化 (NFKC)
        // 将兼容字符（如 ﬀ, ½）转换为标准形式 (ff, 1/2)
        String cleaned = Normalizer.normalize(content, Normalizer.Form.NFKC);

        // 1. 移除控制字符
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("");
        
        // 2. 移除 CID 映射乱码
        cleaned = CID_PATTERN.matcher(cleaned).replaceAll("");

        // 3. 移除常见的页眉页脚行
        cleaned = COMMON_HEADER_FOOTER.matcher(cleaned).replaceAll("");

        // 4. 移除页码行
        cleaned = PAGE_NUMBER_PATTERN.matcher(cleaned).replaceAll("");

        // 5. 移除重复的分隔符
        cleaned = REPEATED_SEPARATORS.matcher(cleaned).replaceAll(" ");

        // 6. 隐私脱敏 (示例：替换为 [EMAIL], [PHONE])
        // 注意：这是不可逆的，如果搜索需要匹配邮箱，则不应脱敏或使用 Hash
        // cleaned = EMAIL_PATTERN.matcher(cleaned).replaceAll("[EMAIL]");
        // cleaned = PHONE_PATTERN.matcher(cleaned).replaceAll("[PHONE]");

        // 7. 智能空白处理：
        // a. 将连续的空格/制表符替换为单个空格
        cleaned = MULTIPLE_SPACES.matcher(cleaned).replaceAll(" ");
        // b. 将连续的 3 个以上换行符替换为 2 个（保留段落结构，去除过多空行）
        cleaned = EXCESSIVE_NEWLINES.matcher(cleaned).replaceAll("\n\n");
        
        return cleaned.trim();
    }

    /**
     * 搜索相似文档
     *
     * @param query 搜索查询语句
     * @return 相似文档列表
     */
    public List<Document> search(String query) {
        return vectorStore.similaritySearch(query);
    }

    /**
     * 结合元数据过滤搜索
     *
     * @param query      搜索关键词
     * @param metaFilter 简单的元数据键值对过滤（仅支持相等匹配）
     * @return 搜索结果
     */
    public List<Document> search(String query, Map<String, Object> metaFilter) {
        if (metaFilter == null || metaFilter.isEmpty()) {
            return search(query);
        }

        // 演示：先搜索再过滤 (Post-filtering)
        // 注意：这在生产中可能效率不高，因为可能会过滤掉所有结果。
        // 生产环境应使用 VectorStore 的 FilterExpression 支持。
        // 但为了兼容性和演示方便，这里使用内存过滤。
        List<Document> results = vectorStore.similaritySearch(query);
        
        return results.stream()
                .filter(doc -> {
                    Map<String, Object> metadata = doc.getMetadata();
                    for (Map.Entry<String, Object> entry : metaFilter.entrySet()) {
                        Object val = metadata.get(entry.getKey());
                        // 简单比较，实际可能需要类型转换
                        if (val == null || !val.toString().equals(entry.getValue().toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }
}
