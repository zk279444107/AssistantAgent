package com.alibaba.assistant.agent.autoconfigure.subagent.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeGeneratorNode#extractCodeFromContent 单测
 *
 * <p>覆盖 LLM 各种典型输出格式，验证代码提取的健壮性。
 */
class CodeGeneratorNodeExtractCodeTest {

    // ==================== 1. 标准 markdown 代码块 ====================

    @Nested
    @DisplayName("标准 markdown 代码块")
    class MarkdownCodeBlock {

        @Test
        @DisplayName("纯 ```python 代码块")
        void pureCodeBlock() {
            String input = "```python\ndef foo():\n    return 42\n```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def foo():"));
            assertFalse(result.contains("```"));
        }

        @Test
        @DisplayName("纯 ``` 代码块（无语言标记）")
        void pureCodeBlockNoLang() {
            String input = "```\ndef bar():\n    pass\n```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def bar():"));
        }

        @Test
        @DisplayName("```py 简写标记")
        void pyShorthand() {
            String input = "```py\ndef baz():\n    return 1\n```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def baz():"));
        }
    }

    // ==================== 2. 自然语言 + 代码块（核心场景） ====================

    @Nested
    @DisplayName("自然语言前缀 + 代码块")
    class NaturalLanguagePrefixWithCodeBlock {

        @Test
        @DisplayName("中文分析 + ```python 代码块（复现实际报错场景）")
        void chineseAnalysisBeforeCodeBlock() {
            String input = "根据需求分析，我需要为项目\"秦逸test项目\"创建一个迭代。\n"
                    + "查看可用工具，需要先查询项目列表获取。\n\n"
                    + "```python\n"
                    + "def create_iteration():\n"
                    + "    project_name = \"秦逸test项目\"\n"
                    + "    return {\"success\": False, \"message\": \"缺少项目ID，无法创建迭代\"}\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def create_iteration():"), "应以函数定义开头，实际: " + result.substring(0, Math.min(50, result.length())));
            assertFalse(result.contains("根据需求分析"), "不应包含自然语言分析");
            assertFalse(result.contains("```"), "不应包含 markdown 标记");
        }

        @Test
        @DisplayName("英文分析 + 代码块")
        void englishAnalysisBeforeCodeBlock() {
            String input = "I'll create a function to search for the project.\n\n"
                    + "```python\n"
                    + "def search_project():\n"
                    + "    return []\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def search_project():"));
            assertFalse(result.contains("I'll create"));
        }

        @Test
        @DisplayName("多段落分析 + 代码块")
        void multiParagraphAnalysis() {
            String input = "首先分析需求。\n\n"
                    + "然后确定使用哪些工具。\n\n"
                    + "最后生成代码如下：\n\n"
                    + "```python\n"
                    + "def my_func():\n"
                    + "    return True\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def my_func():"));
        }
    }

    // ==================== 3. 多代码块 ====================

    @Nested
    @DisplayName("多代码块（取最后一个）")
    class MultipleCodeBlocks {

        @Test
        @DisplayName("两个代码块，取最后一个")
        void twoCodeBlocks() {
            String input = "先看一个错误的版本：\n\n"
                    + "```python\n"
                    + "def wrong():\n"
                    + "    pass\n"
                    + "```\n\n"
                    + "修正后的版本：\n\n"
                    + "```python\n"
                    + "def correct():\n"
                    + "    return 42\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.contains("def correct():"), "应提取最后一个代码块");
            assertFalse(result.contains("def wrong():"), "不应包含第一个代码块");
        }

        @Test
        @DisplayName("三个代码块，取最后一个")
        void threeCodeBlocks() {
            String input = "v1:\n```python\ndef v1():\n    pass\n```\n"
                    + "v2:\n```python\ndef v2():\n    pass\n```\n"
                    + "最终版:\n```python\ndef v3():\n    return 'final'\n```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.contains("def v3():"));
        }
    }

    // ==================== 4. 无 markdown 标记 ====================

    @Nested
    @DisplayName("无 markdown 标记")
    class NoMarkdown {

        @Test
        @DisplayName("纯代码（直接以 def 开头）")
        void pureCode() {
            String input = "def hello():\n    return 'hello'";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertEquals("def hello():\n    return 'hello'", result);
        }

        @Test
        @DisplayName("自然语言 + 裸 def（无代码块包裹）")
        void naturalLanguageThenBareDef() {
            String input = "好的，我来生成代码。\n"
                    + "def do_something():\n"
                    + "    return True";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def do_something():"), "应从 def 处截取");
            assertFalse(result.contains("好的"), "不应包含自然语言");
        }

        @Test
        @DisplayName("纯自然语言（无代码，兜底返回原文）")
        void pureNaturalLanguage() {
            String input = "抱歉，我无法生成代码。";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertEquals("抱歉，我无法生成代码。", result);
        }
    }

    // ==================== 5. 边界场景 ====================

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("null 输入")
        void nullInput() {
            assertNull(CodeGeneratorNode.extractCodeFromContent(null));
        }

        @Test
        @DisplayName("空字符串")
        void emptyString() {
            assertEquals("", CodeGeneratorNode.extractCodeFromContent(""));
        }

        @Test
        @DisplayName("只有空白字符")
        void onlyWhitespace() {
            String result = CodeGeneratorNode.extractCodeFromContent("   \n\n  ");
            // 纯空白内容，trim 后为空，直接返回原值
            assertNotNull(result);
        }

        @Test
        @DisplayName("代码块内含全角字符（字符串中）")
        void fullWidthCharsInString() {
            String input = "```python\n"
                    + "def greet():\n"
                    + "    return '你好，世界！'\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.contains("你好，世界！"), "应保留字符串中的全角字符");
        }

        @Test
        @DisplayName("代码块内含全角字符（注释中）")
        void fullWidthCharsInComment() {
            String input = "```python\n"
                    + "def foo():\n"
                    + "    # 查询项目，获取结果\n"
                    + "    return None\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.contains("# 查询项目，获取结果"), "应保留注释中的全角字符");
        }

        @Test
        @DisplayName("代码前后有大量空行")
        void lotsOfWhitespace() {
            String input = "\n\n\n```python\n\ndef foo():\n    pass\n\n```\n\n\n";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def foo():"));
        }

        @Test
        @DisplayName("空代码块 + 后续代码块，应取后面非空的")
        void emptyCodeBlockFollowedByReal() {
            String input = "```python\n\n```\n\n```python\ndef fallback():\n    pass\n```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            // 策略1：两个代码块，第一个为空被跳过，第二个有效
            assertTrue(result.startsWith("def fallback():"));
        }
    }

    // ==================== 6. 模拟实际 LLM 输出 ====================

    @Nested
    @DisplayName("模拟实际 LLM 输出")
    class RealWorldLLMOutput {

        @Test
        @DisplayName("模拟实际报错的完整 LLM 输出")
        void realWorldCrashCase() {
            // 这是导致线上 SyntaxError 的实际 LLM 输出格式
            String input = "根据需求分析，我需要为项目\"秦逸test项目\"创建一个迭代。"
                    + "查看可用工具，`coop_tools.create_sprint` 需要 `projectId` 参数，"
                    + "但需求中没有提供项目ID，需要先查询项目列表获取。\n"
                    + "\n"
                    + "但是，当前可用工具列表中只有 `coop_tools.create_sprint` 和 `o2_tools.add_iteration`，"
                    + "没有查询项目列表的工具。因此我需要向用户说明缺少必要信息。\n"
                    + "\n"
                    + "```python\n"
                    + "def create_iteration():\n"
                    + "    project_name = \"秦逸test项目\"\n"
                    + "    iteration_name = \"测试迭代2\"\n"
                    + "    owner_info = \"不存在（99999999）\"\n"
                    + "    \n"
                    + "    owner_id_result = llm_tools.call_llm(\n"
                    + "        source_data=owner_info,\n"
                    + "        target_format=\"99999999\",\n"
                    + "        extract_requirement=\"从括号中提取负责人工号，纯数字字符串\"\n"
                    + "    )\n"
                    + "    owner_id = owner_id_result if owner_id_result else \"99999999\"\n"
                    + "    \n"
                    + "    reply_tools.send_message(\n"
                    + "        message=f\"创建迭代需要项目ID，但当前无法查询项目列表。\"\n"
                    + "    )\n"
                    + "    \n"
                    + "    return {\n"
                    + "        \"success\": False,\n"
                    + "        \"message\": \"缺少项目ID，无法创建迭代\",\n"
                    + "        \"projectName\": project_name,\n"
                    + "        \"iterationName\": iteration_name,\n"
                    + "        \"ownerId\": owner_id\n"
                    + "    }\n"
                    + "```";

            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def create_iteration():"),
                    "应以函数定义开头，实际开头: " + result.substring(0, Math.min(60, result.length())));
            assertFalse(result.contains("根据需求分析"), "不应包含自然语言");
            assertTrue(result.contains("reply_tools.send_message"), "应保留函数体");
            assertTrue(result.contains("\"success\": False"), "应保留返回值");
        }

        @Test
        @DisplayName("LLM 只返回代码不带任何解释")
        void llmReturnsOnlyCode() {
            String input = "def simple():\n    return 1 + 1";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertEquals("def simple():\n    return 1 + 1", result);
        }

        @Test
        @DisplayName("LLM 返回代码后面还有解释")
        void codeBlockFollowedByExplanation() {
            String input = "```python\n"
                    + "def calculate():\n"
                    + "    return 42\n"
                    + "```\n\n"
                    + "这个函数会返回42，因为这是宇宙的终极答案。";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def calculate():"));
            assertFalse(result.contains("宇宙的终极答案"), "不应包含代码块后的解释");
        }

        @Test
        @DisplayName("LLM 输出含缩进的复杂函数")
        void complexIndentedFunction() {
            String input = "以下是实现：\n\n```python\n"
                    + "def process_data(**kwargs):\n"
                    + "    results = []\n"
                    + "    for key, value in kwargs.items():\n"
                    + "        if isinstance(value, str):\n"
                    + "            results.append(f\"{key}: {value}\")\n"
                    + "        else:\n"
                    + "            results.append(f\"{key}: {str(value)}\")\n"
                    + "    return {\"processed\": results, \"count\": len(results)}\n"
                    + "```";
            String result = CodeGeneratorNode.extractCodeFromContent(input);
            assertTrue(result.startsWith("def process_data(**kwargs):"));
            assertTrue(result.contains("for key, value in kwargs.items():"));
            assertTrue(result.contains("return {\"processed\": results"));
        }
    }
}


