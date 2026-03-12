package com.alibaba.assistant.agent.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.htmlunit.WebClient;
import org.htmlunit.BrowserVersion;
import org.htmlunit.html.HtmlPage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    // 噪声清洗正则：匹配多余的空白字符（用于段落内，保留段落间空行）
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[ \\t\\x0B\\f]+");
    // 噪声清洗正则：匹配连续的换行符（3个及以上换行替换为2个，保留段落结构）
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");

    // 噪声清洗正则：匹配可能的页码（如单独的数字，或 "Page 1 of 10" 格式）
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^\\s*(\\d+|Page\\s+\\d+\\s+of\\s+\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    // 噪声清洗正则：匹配控制字符（除了换行和制表符）
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    // 噪声清洗正则：匹配连续的重复分隔符（如 "---", "___", "..."）
    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("[-=_*.]{3,}");
    // 噪声清洗正则：匹配 CID 字体映射问题 (如 (cid:123))
    private static final Pattern CID_PATTERN = Pattern.compile("\\(cid:\\d+\\)");
    // 噪声清洗正则：匹配常见的页眉页脚关键词行
    private static final Pattern COMMON_HEADER_FOOTER = Pattern.compile(
            "^\\s*(Copyright|All rights reserved|Confidential|Draft|Internal Use Only).*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // 从 Content-Disposition 提取文件名的正则
    private static final Pattern FILENAME_PATTERN = Pattern.compile("filename=\"?([^\";]*)\"?");

    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;

    // 线程池用于并行向量化存储
    private final ExecutorService executorService = Executors
            .newFixedThreadPool(Math.min(Runtime.getRuntime().availableProcessors(), 4));

    // 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    @Autowired
    public KnowledgeIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        // 优化配置：
        // defaultChunkSize: 800 tokens (约3000字符，适合大多LLM上下文)
        // minChunkSizeChars: 350 chars (避免过小的碎片)
        // minChunkLengthToEmbed: 5 (太短的不嵌入)
        // maxNumChunks: 10000 (保护)
        // keepSeparator: true (保留分隔符)
        this.tokenTextSplitter = new TokenTextSplitter(800, 350, 5, 10000, true);
    }

    /**
     * Ingest content from a URL into the vector store.
     * 优化：使用 HttpURLConnection 模拟浏览器行为，支持重定向、超时和文件名探测
     *
     * @param urlStr   The URL to ingest.
     * @param title    Optional custom title for the document.
     * @param keywords Optional keywords to boost relevance.
     * @return The number of documents added.
     */
    public int ingestUrl(String urlStr, String title, List<String> keywords) {
        HttpURLConnection connection = null;
        try {
            logger.info("Starting ingestion for URL: {}", urlStr);

            // 使用 URI.toURL() 替代 new URL()，避免 URL 编码问题和过时方法警告
            URL url;
            try {
                url = new URI(urlStr).toURL();
            } catch (URISyntaxException e) {
                // 如果直接转换失败，尝试简单编码或回退
                url = new URI(urlStr).toURL();
            }

            connection = (HttpURLConnection) url.openConnection();

            // 设置请求头，模拟浏览器以避免部分网站的 403 拦截
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Accept",
                    "text/html,application/json,application/xhtml+xml,application/xml,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document;q=0.9,*/*;q=0.8");
            connection.setConnectTimeout(10000); // 10秒连接超时
            connection.setReadTimeout(30000); // 30秒读取超时
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 增加重定向处理逻辑（如果HttpURLConnection没自动处理）
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {

                    String newUrl = connection.getHeaderField("Location");
                    logger.info("Redirecting to: {}", newUrl);
                    if (connection != null)
                        connection.disconnect();
                    return ingestUrl(newUrl, title, keywords); // 递归调用新地址
                }
                throw new IOException("Failed to download URL: " + urlStr + ", Response Code: " + responseCode);
            }

            // 尝试探测文件名（对 Tika 识别格式很有帮助）
            String filename = detectFilename(connection, urlStr);
            long contentLength = connection.getContentLengthLong();
            String contentType = connection.getContentType();

            logger.info("URL Connection established. Filename: {}, Type: {}, Length: {}", filename, contentType,
                    contentLength);

            // 如果是 HTML，优先使用 Jsoup 进行 DOM 清洗，并视情况使用 HtmlUnit 动态渲染
            if (contentType != null) {
                if (contentType.toLowerCase().contains("text/html")) {
                    // 关闭当前连接（因为 Jsoup/HtmlUnit 会自己发起连接）
                    connection.disconnect();
                    connection = null;
                    return ingestHtml(urlStr, filename, contentLength, title, keywords);
                } else if (contentType.toLowerCase().contains("application/json")) {
                    // 如果是 JSON 响应，直接解析流
                    // 需要先把流读出来，否则 connect 可能会被自动关闭，或者直接在 try-with-resources 里处理
                    // 这里我们为了复用 processAndStoreDocuments，手动解析
                    try (InputStream inputStream = connection.getInputStream()) {
                        Resource resource = new NamedInputStreamResource(inputStream, filename, urlStr);
                        List<Document> documents = parseJsonDocuments(resource);
                        return processAndStoreDocuments(documents, urlStr, "url-json", contentLength, title, keywords);
                    }
                }
            }

            try (InputStream inputStream = connection.getInputStream()) {
                // 使用自定义 Resource 包装流，提供文件名信息给 Tika
                Resource resource = new NamedInputStreamResource(inputStream, filename, urlStr);
                return processResource(resource, urlStr, "url", contentLength, title, keywords);
            }

        } catch (MalformedURLException e) {
            logger.error("Invalid URL: {}", urlStr, e);
            throw new IllegalArgumentException("Invalid URL: " + urlStr, e);
        } catch (Exception e) {
            logger.error("Failed to ingest URL: {}", urlStr, e);
            throw new RuntimeException("Failed to ingest URL: " + urlStr, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 探测文件名：优先 Content-Disposition，其次 URL 路径，最后默认值
     */
    private String detectFilename(HttpURLConnection connection, String urlStr) {
        String filename = null;

        // 1. 尝试从 Content-Disposition 获取
        String disposition = connection.getHeaderField("Content-Disposition");
        if (StringUtils.hasText(disposition)) {
            Matcher matcher = FILENAME_PATTERN.matcher(disposition);
            if (matcher.find()) {
                filename = matcher.group(1);
            }
        }

        // 2. 尝试从 URL 路径获取
        if (!StringUtils.hasText(filename)) {
            try {
                String path = new URI(urlStr).getPath();
                if (StringUtils.hasText(path)) {
                    int lastSlash = path.lastIndexOf('/');
                    if (lastSlash != -1 && lastSlash < path.length() - 1) {
                        filename = path.substring(lastSlash + 1);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. 根据 Content-Type 猜测后缀
        if (!StringUtils.hasText(filename)) {
            String contentType = connection.getContentType();
            if (StringUtils.hasText(contentType)) {
                if (contentType.contains("html")) {
                    filename = "index.html";
                } else if (contentType.contains("pdf")) {
                    filename = "document.pdf";
                }
            }
        }

        return StringUtils.hasText(filename) ? filename : "unknown_file";
    }

    /**
     * Ingest content from an uploaded file into the vector store.
     *
     * @param file     The uploaded file.
     * @param title    Optional custom title for the document.
     * @param keywords Optional keywords to boost relevance.
     * @return The number of documents added.
     */
    public int ingestFile(MultipartFile file, String title, List<String> keywords) {
        try {
            logger.info("Starting ingestion for file: {}", file.getOriginalFilename());
            Resource resource = new InputStreamResource(file.getInputStream());
            return processResource(resource, file.getOriginalFilename(), "file", file.getSize(), title, keywords);
        } catch (IOException e) {
            logger.error("Failed to read file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to read file: " + file.getOriginalFilename(), e);
        } catch (Exception e) {
            logger.error("Failed to ingest file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to ingest file: " + file.getOriginalFilename(), e);
        }
    }

    /**
     * 处理资源：读取 -> 清洗 -> 切分 -> 向量化 -> 存储
     */
    private int processResource(Resource resource, String sourceName, String sourceType, long size, String title,
            List<String> keywords) {
        
        List<Document> documents;
        
        // 1. 如果是 JSON 文件，使用 Jackson 手动解析以保留结构化信息
        if (sourceName != null && sourceName.toLowerCase().endsWith(".json")) {
            try {
                documents = parseJsonDocuments(resource);
            } catch (Exception e) {
                logger.warn("JSON parsing failed for {}, falling back to Tika. Error: {}", sourceName, e.getMessage());
                // Fallback to Tika
                DocumentReader reader = new TikaDocumentReader(resource);
                documents = reader.get();
            }
        } else {
            // 2. 其他格式使用 Tika 读取文档
            // TikaDocumentReader 会根据 Resource 的文件名或流内容自动探测格式
            DocumentReader reader = new TikaDocumentReader(resource);
            documents = reader.get();
        }

        if (documents.isEmpty()) {
            logger.warn("KnowledgeIngestionService#processResource - reason=no content found, source={}", sourceName);
            return 0;
        }

        return processAndStoreDocuments(documents, sourceName, sourceType, size, title, keywords);
    }

    /**
     * 解析 JSON 文档，尝试提取结构化字段
     * 假设 JSON 可能是单个对象或对象数组
     */
    private List<Document> parseJsonDocuments(Resource resource) throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(resource.getInputStream());
        
        // 检查是否为 OpenAPI/Swagger 文档
        if (rootNode.has("openapi") || rootNode.has("swagger")) {
            logger.info("Detected OpenAPI/Swagger document structure.");
            return parseOpenApiDocuments(rootNode);
        }

        // 检查是否为特定的单个接口文档 (ERP/API 平台格式)
        if (isSingleApiDocument(rootNode)) {
            logger.info("Detected Single API document structure.");
            return convertSingleApiToDocuments(rootNode);
        }

        List<Document> docs = new ArrayList<>();

        if (rootNode.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode node : rootNode) {
                docs.add(convertJsonNodeToDocument(node));
            }
        } else if (rootNode.isObject()) {
            docs.add(convertJsonNodeToDocument(rootNode));
        }
        
        return docs;
    }

    /**
     * 判断是否为特定的单个接口文档格式（或接口列表）
     */
    private boolean isSingleApiDocument(com.fasterxml.jackson.databind.JsonNode root) {
        if (root.has("data")) {
            com.fasterxml.jackson.databind.JsonNode data = root.get("data");
            // 情况1：data 是对象，且包含 apiName 和 apiUrl
            if (data.isObject()) {
                return data.has("apiName") && data.has("apiUrl");
            } 
            // 情况2：data 是数组，且第一个元素包含 apiName 和 apiUrl
            else if (data.isArray() && !data.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode firstItem = data.get(0);
                return firstItem.has("apiName") && firstItem.has("apiUrl");
            }
        }
        return false;
    }

    /**
     * 转换接口文档（支持单个对象或数组）为 Document
     */
    private List<Document> convertSingleApiToDocuments(com.fasterxml.jackson.databind.JsonNode root) {
        List<Document> documents = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode data = root.get("data");

        if (data.isArray()) {
            // 如果是数组，遍历处理每个接口
            for (com.fasterxml.jackson.databind.JsonNode apiNode : data) {
                // 构造一个临时的 root 节点结构，以便复用逻辑（或者直接提取处理逻辑）
                documents.addAll(processSingleApiNode(apiNode));
            }
        } else if (data.isObject()) {
            // 如果是单个对象
            documents.addAll(processSingleApiNode(data));
        }
        
        return documents;
    }

    /**
     * 处理单个 API 节点数据，生成三位一体的向量文档
     */
    private List<Document> processSingleApiNode(com.fasterxml.jackson.databind.JsonNode data) {
        List<Document> documents = new ArrayList<>();
        
        String apiId = data.path("id").asText();
        String apiName = data.path("apiName").asText("");
        String apiUrl = data.path("apiUrl").asText("");
        String method = data.path("erpMethod").asText("GET").toUpperCase();
        String erpService = data.path("erpService").asText("");
        String erpLineName = data.path("erpLineName").asText("");
        String description = data.path("description").asText("");
        
        // 共享的元数据
        Map<String, Object> baseMetadata = new HashMap<>();
        baseMetadata.put("api_id", apiId);
        baseMetadata.put("api_name", apiName);
        baseMetadata.put("api_url", apiUrl);
        baseMetadata.put("method", method);
        baseMetadata.put("service", erpService);
        baseMetadata.put("erp_line", erpLineName);
        baseMetadata.put("接口名称", apiName);
        baseMetadata.put("接口URL", apiUrl);
        baseMetadata.put("title", apiName);
        
        // A. 意图层 (Intent Chunk)
        StringBuilder intentContent = new StringBuilder();
        intentContent.append(String.format("接口名称：%s\n", apiName));
        intentContent.append(String.format("访问路径：[%s] %s\n", method, apiUrl));
        intentContent.append(String.format("所属业务：%s - %s\n", erpLineName, data.path("moduleOneName").asText("")));
        if (StringUtils.hasText(description)) {
            intentContent.append(String.format("描述：%s\n", description));
        }
        if (data.has("erpName")) {
            intentContent.append(String.format("ERP名称：%s\n", data.path("erpName").asText()));
        }
        
        Map<String, Object> intentMetadata = new HashMap<>(baseMetadata);
        intentMetadata.put("chunk_type", "intent");
        intentMetadata.put("type", "api_intent");
        List<String> keywords = new ArrayList<>();
        keywords.add(apiName);
        keywords.add(apiUrl);
        intentMetadata.put("matched_keywords", keywords);
        
        documents.add(new Document(generateId(intentContent.toString(), "intent:" + apiId), intentContent.toString(), intentMetadata));
        
        // B. 字段语义层 (Field Semantic Chunk)
        StringBuilder fieldContent = new StringBuilder();
        fieldContent.append(String.format("接口 %s (%s) 的详细字段定义：\n\n", apiName, apiUrl));
        
        if (data.has("requestHeader")) {
            fieldContent.append("### 请求头 (Headers)\n");
            flattenFields(fieldContent, data.get("requestHeader"), "");
        }
        
        if (data.has("requestBody")) {
            fieldContent.append("\n### 请求参数 (Request)\n");
            flattenFields(fieldContent, data.get("requestBody"), "");
        }
        
        if (data.has("responseBody")) {
            fieldContent.append("\n### 响应参数 (Response)\n");
            flattenFields(fieldContent, data.get("responseBody"), "");
        }
        
        Map<String, Object> fieldMetadata = new HashMap<>(baseMetadata);
        fieldMetadata.put("chunk_type", "field_semantic");
        fieldMetadata.put("type", "api_field");
        
        documents.add(new Document(generateId(fieldContent.toString(), "field:" + apiId), fieldContent.toString(), fieldMetadata));
        
        // C. 示例代码层 (Instruction Chunk)
        StringBuilder instructionContent = new StringBuilder();
        instructionContent.append(String.format("如何调用接口：%s\n", apiName));
        instructionContent.append(String.format("HTTP Method: %s\n", method));
        instructionContent.append(String.format("URL: %s\n\n", apiUrl));
        
        instructionContent.append("### 核心必填参数\n");
        List<String> requiredParams = new ArrayList<>();
        extractRequiredParams(data.get("requestBody"), "", requiredParams);
        if (requiredParams.isEmpty()) {
            instructionContent.append("无必填参数\n");
        } else {
            requiredParams.forEach(p -> instructionContent.append("- ").append(p).append("\n"));
        }
        
        instructionContent.append("\n### 请求示例 (JSON)\n");
        if (data.has("requestBodyDemo")) {
            instructionContent.append("```json\n").append(data.path("requestBodyDemo").asText("{}")).append("\n```\n");
        } else {
            instructionContent.append("```json\n{}\n```\n");
        }
        
        instructionContent.append("\n### 响应示例 (JSON)\n");
        if (data.has("responseBodyDemo")) {
            instructionContent.append("```json\n").append(data.path("responseBodyDemo").asText("{}")).append("\n```\n");
        }
        
        Map<String, Object> instructionMetadata = new HashMap<>(baseMetadata);
        instructionMetadata.put("chunk_type", "instruction");
        instructionMetadata.put("type", "api_instruction");
        
        documents.add(new Document(generateId(instructionContent.toString(), "instruction:" + apiId), instructionContent.toString(), instructionMetadata));
        
        return documents;
    }

    /**
     * 递归展开字段，生成 路径: 描述 (枚举值) 的形式
     */
    private void flattenFields(StringBuilder sb, com.fasterxml.jackson.databind.JsonNode fields, String prefix) {
        if (fields == null || !fields.isArray()) return;
        
        fields.forEach(field -> {
            String name = field.path("name").asText();
            String currentPath = prefix.isEmpty() ? name : prefix + "." + name;
            String type = field.path("type").asText();
            String desc = field.path("description").asText().replaceAll("\\s+", " ").trim();
            boolean must = field.path("must").asBoolean(false);
            
            sb.append(String.format("- `%s` (%s): %s %s\n", currentPath, type, desc, must ? "[必填]" : ""));
            
            if (field.has("children") && !field.get("children").isEmpty()) {
                flattenFields(sb, field.get("children"), currentPath);
            }
        });
    }
    
    /**
     * 提取必填参数
     */
    private void extractRequiredParams(com.fasterxml.jackson.databind.JsonNode fields, String prefix, List<String> requiredList) {
        if (fields == null || !fields.isArray()) return;
        
        fields.forEach(field -> {
            boolean must = field.path("must").asBoolean(false);
            String name = field.path("name").asText();
            String currentPath = prefix.isEmpty() ? name : prefix + "." + name;
            
            if (must) {
                String desc = field.path("description").asText().replaceAll("\\s+", " ").trim();
                requiredList.add(String.format("`%s`: %s", currentPath, desc));
            }
            
            if (field.has("children") && !field.get("children").isEmpty()) {
                extractRequiredParams(field.get("children"), currentPath, requiredList);
            }
        });
    }

    /**
     * 专门解析 OpenAPI 文档，按 Path + Method 拆分
     */
    private List<Document> parseOpenApiDocuments(com.fasterxml.jackson.databind.JsonNode root) {
        List<Document> docs = new ArrayList<>();
        String apiTitle = root.path("info").path("title").asText("OpenAPI Specification");
        String version = root.path("info").path("version").asText("");
        
        if (root.has("paths")) {
            com.fasterxml.jackson.databind.JsonNode paths = root.get("paths");
            paths.fields().forEachRemaining(pathEntry -> {
                String path = pathEntry.getKey();
                com.fasterxml.jackson.databind.JsonNode pathNode = pathEntry.getValue();
                
                pathNode.fields().forEachRemaining(methodEntry -> {
                    String method = methodEntry.getKey().toUpperCase();
                    // 只处理 HTTP 方法
                    if (List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE").contains(method)) {
                        com.fasterxml.jackson.databind.JsonNode operation = methodEntry.getValue();
                        docs.add(convertOpenApiOperationToDocument(apiTitle, version, path, method, operation));
                    }
                });
            });
        }
        
        // 也可以选择将 components/schemas 单独提取为文档，用于上下文注入（这里暂时略过，重点在 Path 拆分）
        return docs;
    }

    private Document convertOpenApiOperationToDocument(String apiTitle, String version, String path, String method, com.fasterxml.jackson.databind.JsonNode operation) {
        Map<String, Object> metadata = new HashMap<>();
        StringBuilder content = new StringBuilder();
        
        String summary = operation.path("summary").asText("");
        String description = operation.path("description").asText("");
        
        // 1. 构建语义标题 (Level 1 & 2)
        content.append(String.format("# %s %s\n", method, path));
        if (StringUtils.hasText(summary)) {
            content.append(String.format("**Summary**: %s\n", summary));
        }
        content.append(String.format("**API**: %s (%s)\n\n", apiTitle, version));
        
        if (StringUtils.hasText(description)) {
            content.append(String.format("**Description**: %s\n\n", description));
        }
        
        // 2. 提取参数
        if (operation.has("parameters")) {
            content.append("### Parameters\n");
            for (com.fasterxml.jackson.databind.JsonNode param : operation.get("parameters")) {
                String name = param.path("name").asText();
                String in = param.path("in").asText();
                String desc = param.path("description").asText();
                boolean required = param.path("required").asBoolean(false);
                String type = param.path("schema").path("type").asText("string");
                
                content.append(String.format("- `%s` (%s): %s %s [Type: %s]\n", 
                        name, in, desc, required ? "(Required)" : "", type));
            }
            content.append("\n");
        }
        
        // 3. 提取 Request Body
        if (operation.has("requestBody")) {
            content.append("### Request Body\n");
            com.fasterxml.jackson.databind.JsonNode contentNode = operation.path("requestBody").path("content");
            if (contentNode.has("application/json")) {
                com.fasterxml.jackson.databind.JsonNode schema = contentNode.path("application/json").path("schema");
                content.append(simplifySchema(schema)).append("\n");
            } else {
                content.append("See schema definition.\n");
            }
            content.append("\n");
        }
        
        // 4. 提取 Responses
        if (operation.has("responses")) {
            content.append("### Responses\n");
            operation.get("responses").fields().forEachRemaining(entry -> {
                String status = entry.getKey();
                String desc = entry.getValue().path("description").asText();
                content.append(String.format("- **%s**: %s\n", status, desc));
            });
        }
        
        // 5. 设置元数据
        metadata.put("path", path);
        metadata.put("method", method);
        metadata.put("summary", summary);
        metadata.put("api_title", apiTitle);
        metadata.put("type", "openapi_operation");
        
        if (operation.has("tags")) {
            List<String> tags = new ArrayList<>();
            operation.get("tags").forEach(t -> tags.add(t.asText()));
            metadata.put("tags", tags);
            metadata.put("matched_keywords", tags); // 复用关键词匹配逻辑
        }
        
        return new Document(content.toString(), metadata);
    }

    /**
     * 将 JSON Schema 转换为简化的 Markdown/YAML 表示
     */
    private String simplifySchema(com.fasterxml.jackson.databind.JsonNode schema) {
        StringBuilder sb = new StringBuilder();
        // 简单递归或格式化，这里做简化处理
        if (schema.has("type") && "object".equals(schema.get("type").asText())) {
            sb.append("Type: Object\n");
            if (schema.has("properties")) {
                sb.append("Properties:\n");
                schema.get("properties").fields().forEachRemaining(entry -> {
                    String propName = entry.getKey();
                    com.fasterxml.jackson.databind.JsonNode propDef = entry.getValue();
                    String type = propDef.path("type").asText("any");
                    String desc = propDef.path("description").asText("");
                    sb.append(String.format("  - `%s` (%s): %s\n", propName, type, desc));
                });
            }
        } else if (schema.has("type") && "array".equals(schema.get("type").asText())) {
            sb.append("Type: Array of ");
            if (schema.has("items")) {
                sb.append(simplifySchema(schema.get("items")).replace("\n", " "));
            }
            sb.append("\n");
        } else if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            sb.append("Reference: ").append(ref.substring(ref.lastIndexOf('/') + 1)).append("\n");
        } else {
            sb.append("Type: ").append(schema.path("type").asText("any")).append("\n");
        }
        return sb.toString();
    }

    private Document convertJsonNodeToDocument(com.fasterxml.jackson.databind.JsonNode node) {
        Map<String, Object> metadata = new HashMap<>();
        StringBuilder contentBuilder = new StringBuilder();
        
        // 尝试提取常见元数据字段
        if (node.has("title")) {
            String title = node.get("title").asText();
            metadata.put("title", title);
            contentBuilder.append("Title: ").append(title).append("\n\n");
        }
        if (node.has("url")) metadata.put("url", node.get("url").asText());
        if (node.has("source")) metadata.put("source", node.get("source").asText());
        if (node.has("author")) metadata.put("author", node.get("author").asText());
        if (node.has("date")) metadata.put("date", node.get("date").asText());
        if (node.has("tags")) {
             com.fasterxml.jackson.databind.JsonNode tagsNode = node.get("tags");
             if (tagsNode.isArray()) {
                 List<String> tags = new ArrayList<>();
                 tagsNode.forEach(t -> tags.add(t.asText()));
                 metadata.put("tags", tags);
                 contentBuilder.append("Tags: ").append(String.join(", ", tags)).append("\n");
             } else {
                 String tagStr = tagsNode.asText();
                 metadata.put("tags", tagStr);
                 contentBuilder.append("Tags: ").append(tagStr).append("\n");
             }
        }
        
        // 提取主要内容
        String content = "";
        if (node.has("content")) {
            content = node.get("content").asText();
        } else if (node.has("text")) {
            content = node.get("text").asText();
        } else if (node.has("body")) {
            content = node.get("body").asText();
        } else if (node.has("description")) {
            content = node.get("description").asText();
        } else {
            // 如果没有特定内容字段，序列化整个节点
            content = node.toPrettyString();
        }
        
        contentBuilder.append(content);
        
        // 将剩余字段放入 metadata
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!metadata.containsKey(key) && !key.equals("content") && !key.equals("text") && !key.equals("body") && !key.equals("description")) {
                com.fasterxml.jackson.databind.JsonNode value = entry.getValue();
                if (value.isValueNode()) {
                    metadata.put(key, value.asText());
                }
            }
        });
        
        // 将剩余字段放入 metadata
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!metadata.containsKey(key) && !key.equals("content") && !key.equals("text") && !key.equals("body") && !key.equals("description")) {
                com.fasterxml.jackson.databind.JsonNode value = entry.getValue();
                if (value.isValueNode()) {
                    metadata.put(key, value.asText());
                }
            }
        });
        
        return new Document(contentBuilder.toString(), metadata);
    }

    /**
     * 处理 HTML 内容：尝试 Jsoup，如果内容过少则尝试 HtmlUnit 动态渲染
     */
    private int ingestHtml(String urlStr, String sourceName, long size, String title, List<String> keywords) {
        String content = "";
        try {
            content = fetchContentWithJsoup(urlStr);
        } catch (Exception e) {
            logger.warn("Jsoup fetch failed for {}, trying HtmlUnit fallback. Error: {}", urlStr, e.getMessage());
        }

        // 简单判断：如果提取内容少于 200 字符，可能是 SPA 或 JS 渲染页面，或者 Jsoup 失败
        if (content.length() < 200) {
            logger.info("Content length is small ({}), attempting dynamic rendering with HtmlUnit for: {}",
                    content.length(), urlStr);
            try {
                String dynamicContent = fetchContentWithHtmlUnit(urlStr);
                if (dynamicContent.length() > content.length()) {
                    content = dynamicContent;
                }
            } catch (Exception e) {
                logger.warn("HtmlUnit dynamic rendering failed, falling back to Jsoup content. Error: {}",
                        e.getMessage());
            }
        }

        if (!StringUtils.hasText(content)) {
            logger.warn("Extracted empty content from: {}", urlStr);
            return 0;
        }

        Document doc = new Document(content);
        List<Document> documents = new ArrayList<>();
        documents.add(doc);

        return processAndStoreDocuments(documents, sourceName, "url-html", size, title, keywords);
    }

    /**
     * 使用 Jsoup 获取静态内容并清洗
     */
    private String fetchContentWithJsoup(String urlStr) {
        try {
            logger.info("Using Jsoup to fetch: {}", urlStr);
            org.jsoup.nodes.Document jsoupDoc = Jsoup.connect(urlStr)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(10000)
                    .get();

            return cleanAndExtractText(jsoupDoc);
        } catch (IOException e) {
            logger.error("Jsoup fetch failed for: {}", urlStr, e);
            throw new RuntimeException("Jsoup fetch failed: " + urlStr, e);
        }
    }

    /**
     * 使用 HtmlUnit 获取动态渲染内容并清洗
     */
    private String fetchContentWithHtmlUnit(String urlStr) {
        try (WebClient webClient = new WebClient(BrowserVersion.CHROME)) {
            // 配置 WebClient 模拟现代浏览器行为
            webClient.getOptions().setCssEnabled(true); // 启用 CSS，很多 SPA 依赖 CSS 控制显示
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webClient.getOptions().setDownloadImages(false); // 禁用图片下载
            webClient.getOptions().setPopupBlockerEnabled(true);
            webClient.getOptions().setTimeout(30000);
            webClient.setJavaScriptTimeout(30000);
            // 启用 AJAX 控制器
            webClient.setAjaxController(new org.htmlunit.NicelyResynchronizingAjaxController());

            logger.info("Using HtmlUnit to fetch dynamic content: {}", urlStr);
            HtmlPage page = webClient.getPage(urlStr);

            // 等待 JS 执行 (简单的等待策略)
            webClient.waitForBackgroundJavaScript(5000);

            // 额外等待策略：如果内容仍然很短，继续等待
            int maxWait = 5;
            for (int i = 0; i < maxWait; i++) {
                String currentXml = page.asXml();
                org.jsoup.nodes.Document tempDoc = Jsoup.parse(currentXml);
                String tempText = cleanAndExtractText(tempDoc);
                if (tempText.length() > 200) {
                    return tempText; // 内容足够，直接返回
                }
                webClient.waitForBackgroundJavaScript(2000);
            }

            // 将 HtmlUnit 的页面转为 Jsoup 文档进行统一清洗
            String xml = page.asXml();
            org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(xml);

            return cleanAndExtractText(jsoupDoc);
        } catch (Exception e) {
            // 记录警告而非抛出运行时异常中断主流程（如果作为备选方案）
            logger.warn("HtmlUnit fetch failed for {}: {}", urlStr, e.getMessage());
            // 如果 HtmlUnit 失败，返回空字符串，让调用方决定是否使用 Jsoup 的结果
            return "";
        }
    }

    /**
     * 深度 DOM 清洗与结构化文本提取
     */
    private String cleanAndExtractText(org.jsoup.nodes.Document doc) {
        // 1. 移除无关元素
        doc.select(
                "script, style, noscript, iframe, svg, header, footer, nav, aside, form, input, button, select, textarea, embed, object")
                .remove();

        // 2. 移除可能的广告/侧边栏/评论区 (基于常见 class/id 命名)
        // 注意：这可能会误伤，需谨慎使用。这里仅作为示例，匹配比较明显的特征
        doc.select(
                "[class*='ad-'], [id*='ad-'], [class*='sidebar'], [id*='sidebar'], [class*='comment'], [id*='comment']")
                .remove();

        // 3. 结构化文本提取：保留块级元素的换行
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    TextNode textNode = (TextNode) node;
                    String text = textNode.text().trim();
                    if (!text.isEmpty()) {
                        sb.append(text).append(" ");
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if (element.isBlock() || element.tagName().equals("br")) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                            sb.append("\n");
                        }
                    }
                }
            }
        }, doc.body());

        return sb.toString();
    }

    /**
     * 核心处理逻辑：清洗 -> 切分 -> 向量化 -> 存储
     * 优化：并行批处理 + 重试机制 + 关键词增强 + 标题增强
     */
    private int processAndStoreDocuments(List<Document> documents, String sourceName, String sourceType, long size,
            String title, List<String> keywords) {
        
        // 区分处理：结构化 API 文档 vs 普通文档
        // 结构化 API 文档已经过精细分块和格式化，不应再进行清洗和切分，以免破坏语义结构
        List<Document> docsToStore;
        
        boolean isStructuredApiDoc = !documents.isEmpty() && 
            (documents.get(0).getMetadata().containsKey("chunk_type") || 
             "single_api_doc".equals(documents.get(0).getMetadata().get("type")) ||
             "openapi_operation".equals(documents.get(0).getMetadata().get("type")));

        if (isStructuredApiDoc) {
            logger.info("Detected structured API documents, skipping cleaner and splitter.");
            docsToStore = documents.stream()
                .map(doc -> {
                    // 仅做必要的元数据补充
                    Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                    metadata.put("source", sourceName);
                    metadata.put("source_type", sourceType);
                    if (size > 0) metadata.put("file_size", size);
                    metadata.put("ingestion_timestamp", System.currentTimeMillis());
                    
                    // 即使是 API 文档，如果用户手动传了额外的 keywords，也可以合并进去
                    if (keywords != null && !keywords.isEmpty()) {
                        // 合并到 matched_keywords
                        List<String> existing = (List<String>) metadata.getOrDefault("matched_keywords", new ArrayList<>());
                        List<String> newKeywords = new ArrayList<>(existing);
                        newKeywords.addAll(keywords);
                        metadata.put("matched_keywords", newKeywords.stream().distinct().collect(Collectors.toList()));
                    }
                    
                    return new Document(doc.getId(), doc.getText(), metadata);
                })
                .collect(Collectors.toList());
        } else {
            // 常规文档处理流程：清洗 -> 增强 -> 切分
            List<Document> preProcessedDocs = documents.parallelStream()
                    .map(doc -> {
                        String cleanContent = cleanText(doc.getText());
                        if (cleanContent.isEmpty()) {
                            return null;
                        }
                        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                        metadata.put("source", sourceName);
                        metadata.put("source_type", sourceType);
                        if (size > 0) {
                            metadata.put("file_size", size);
                        }
                        metadata.put("ingestion_timestamp", System.currentTimeMillis());
    
                        // 标题增强
                        if (StringUtils.hasText(title)) {
                            metadata.put("title", title);
                        }
    
                        // 关键词匹配逻辑
                        String finalContent = cleanContent;
                        if (keywords != null && !keywords.isEmpty()) {
                            List<String> matched = keywords.stream()
                                    .filter(k -> StringUtils.hasText(k)
                                            && cleanContent.toLowerCase().contains(k.toLowerCase()))
                                    .collect(Collectors.toList());
    
                            if (!matched.isEmpty()) {
                                metadata.put("matched_keywords", matched);
                                // 增强内容：将匹配的关键词放在最前面，提高向量匹配权重
                                String boostHeader = "Keywords: " + String.join(", ", matched) + "\n\n";
                                finalContent = boostHeader + cleanContent;
                            }
                        }
    
                        String id = generateId(finalContent, sourceName);
                        return new Document(id, finalContent, metadata);
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            
            if (preProcessedDocs.isEmpty()) {
                logger.warn("KnowledgeIngestionService#processAndStoreDocuments - reason=empty after cleaning, source={}", sourceName);
                return 0;
            }
            
            // 3. 切分文档
            docsToStore = tokenTextSplitter.apply(preProcessedDocs);
        }

        // 4. 批量存储到向量数据库
        // 性能优化：分批并行处理
        int batchSize = 64;
        List<List<Document>> batches = IntStream.range(0, (docsToStore.size() + batchSize - 1) / batchSize)
                .mapToObj(i -> docsToStore.subList(i * batchSize,
                        Math.min((i + 1) * batchSize, docsToStore.size())))
                .collect(Collectors.toList());

        logger.info("Starting batch ingestion. Total documents: {}, Batches: {}", docsToStore.size(),
                batches.size());

        // 使用 CompletableFuture 并行提交批次
        List<CompletableFuture<Void>> futures = batches.stream()
                .map(batch -> CompletableFuture.runAsync(() -> ingestBatchWithRetry(batch, sourceName),
                        executorService))
                .collect(Collectors.toList());

        try {
            // 等待所有批次完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            logger.info("All batches processed for source: {}", sourceName);
        } catch (Exception e) {
            logger.error("Error during parallel batch ingestion", e);
            throw new RuntimeException("Parallel ingestion failed", e);
        }

        return docsToStore.size();
    }

    /**
     * 带重试机制的批次写入
     */
    private void ingestBatchWithRetry(List<Document> batch, String sourceName) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                vectorStore.add(batch);
                logger.debug("Batch stored successfully. Size: {}, Source: {}", batch.size(), sourceName);
                return;
            } catch (Exception e) {
                attempt++;
                logger.warn("Batch ingestion failed (attempt {}/{}). Error: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt >= MAX_RETRIES) {
                    logger.error("Batch ingestion failed after {} attempts. Dropping batch of {} documents.",
                            MAX_RETRIES, batch.size(), e);
                    // 根据业务需求，这里可以选择抛出异常中断整个流程，或者记录失败日志继续
                    throw new RuntimeException("Failed to ingest batch after retries", e);
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS * attempt); // 线性或指数退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
            }
        }
    }

    /**
     * 生成基于内容的确定性ID
     */
    private String generateId(String content, String sourceName) {
        String input = sourceName + ":" + content;
        return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
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

        // 6. 智能空白处理：
        // a. 将连续的空格/制表符替换为单个空格
        cleaned = MULTIPLE_SPACES.matcher(cleaned).replaceAll(" ");
        // b. 将连续的 3 个以上换行符替换为 2 个（保留段落结构，去除过多空行）
        cleaned = EXCESSIVE_NEWLINES.matcher(cleaned).replaceAll("\n\n");

        return cleaned.trim();
    }

    /**
     * 内部辅助类：携带文件名的 InputStreamResource
     * 帮助 Tika 更准确地识别文件格式
     */
    private static class NamedInputStreamResource extends InputStreamResource {
        private final String filename;
        private final String description;

        public NamedInputStreamResource(InputStream inputStream, String filename, String description) {
            super(inputStream);
            this.filename = filename;
            this.description = description;
        }

        @Override
        public String getFilename() {
            return this.filename;
        }

        @Override
        public String getDescription() {
            return this.description != null ? this.description : super.getDescription();
        }
    }
}
