package com.lincode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lincode.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent 循环的退出预算。
 *
 * 设计目标是把"是否继续下一轮"的主导权交给 LLM 自己——只要它返回 content 不再调用工具，
 * 循环就退出。本类只承担三种"保险阀"职责，避免模型在异常情况下无限重复同一动作：
 *
 * 1. Token 预算：累计 input + output token 超过阈值后强制收尾（**默认无限**，仅显式配置时生效）
 * 2. 停滞检测：识别连续无进展调用、无意义结果窗口变化，以及 ABAB / ABCABC 周期循环
 * 3. 硬轮数兜底：累计迭代轮数超过 hardMaxIterations，作为兜底防御
 *
 * 这三个条件按"先到先触发"判定，任何一个命中都会让循环结束。
 *
 * 配置读取顺序（以 {@link #fromSystemProperties()} 为准）：
 * 1. 系统属性：{@code lincode.react.token.budget} / {@code lincode.react.stagnation.window} /
 *    {@code lincode.react.hard.max.iterations}
 * 2. 默认值：token 预算 = Integer.MAX_VALUE（实质不限）/ 连续 3 次相同工具调用 / 50 轮
 *
 * 设计取舍：长上下文模型（GLM-5.1 200k / DeepSeek V4 1M）配合套餐用户的"无限 token"诉求，
 * 默认不再以 80% × window 为硬限——让 LLM 自然停在它该停的地方。需要严格成本控制的
 * 场景（CI / 自动化批跑）通过 {@code -Dlincode.react.token.budget=N} 显式启用。
 * 死循环防护交给 stagnation 检测和 hardMaxIterations 两道兜底。
 */
public class AgentBudget {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CYCLE_PERIOD = 3;

    /**
     * 只控制返回规模或展示范围的参数。它们发生变化时不能单独证明任务取得了进展；
     * 只有对应工具结果也没有新增信息时，才会被视为同一轮有效操作。
     */
    private static final Set<String> RESULT_WINDOW_ARGUMENTS = Set.of(
            "limit", "max_results", "head_limit", "max_chars", "top_k", "context_lines"
    );

    public enum ExitReason {
        WITHIN_BUDGET,
        TOKEN_BUDGET_EXCEEDED,
        STAGNATION_DETECTED,
        HARD_ITERATION_LIMIT
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;

    private final Deque<ToolRound> recentToolRounds = new ArrayDeque<>();
    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private boolean stagnant;
    private String stagnationDetail = "";

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
    }

    public static AgentBudget fromSystemProperties() {
        return fromLlmClient(null);
    }

    public static AgentBudget fromLlmClient(LlmClient llmClient) {
        // ContextProfile 仍按 80% × window 计算 agentTokenBudget，用于 /context 与 token stats 的"软提示"显示；
        // 但 AgentBudget 的硬限默认走 Integer.MAX_VALUE，避免长上下文 + 套餐用户被预算墙卡住。
        // 显式 -Dlincode.react.token.budget=N 仍可启用硬预算，覆盖默认。
        return new AgentBudget(
                readIntProperty("lincode.react.token.budget", Integer.MAX_VALUE),
                readIntProperty("lincode.react.stagnation.window", DEFAULT_STAGNATION_WINDOW),
                readIntProperty("lincode.react.hard.max.iterations", DEFAULT_HARD_MAX_ITERATIONS)
        );
    }

    /** 进入新一轮迭代，返回当前轮次（从 1 开始）。 */
    public int beginIteration() {
        return ++iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        recordTokens(inputTokens, outputTokens, 0);
    }

    public void recordTokens(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
        this.totalCachedInputTokens += Math.max(0, cachedInputTokens);
    }

    /**
     * 兼容只提供工具调用、不提供结果的调用方。此入口仅能识别规范化后的精确重复，
     * 无法判断辅助参数变化是否真的带来了新信息；生产 ReAct 路径应调用
     * {@link #recordToolRound(List, List)}。
     */
    public void recordToolCalls(List<LlmClient.ToolCall> toolCalls) {
        recordToolRound(toolCalls, null);
    }

    /**
     * 在工具执行完成后记录一整轮调用和结果，并判断是否进入停滞。
     *
     * 停滞条件：
     * 1. 最近 stagnationWindow 轮的有效调用相同，且工具结果没有新增信息；
     * 2. 最近两段长度为 2 或 3 的有效调用序列完全重复（ABAB / ABCABC），
     *    且对应工具结果没有新增信息。
     *
     * 参数 JSON 会先排序字段以消除纯格式差异；limit / max_results / top_k 等只控制
     * 返回窗口的参数会从核心签名中移除，但只有结果也等价时才会据此判定停滞，
     * 避免把正常分页或扩大读取范围误判为死循环。
     *
     * 一旦判定为停滞，状态会保持，后续 {@link #check()} 会返回 STAGNATION_DETECTED。
     */
    public void recordToolRound(List<LlmClient.ToolCall> toolCalls, List<String> toolResults) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            recentToolRounds.clear();
            return;
        }

        ToolRound round = ToolRound.from(toolCalls, toolResults);
        recentToolRounds.addLast(round);
        int historyLimit = Math.max(stagnationWindow, MAX_CYCLE_PERIOD * 2);
        while (recentToolRounds.size() > historyLimit) {
            recentToolRounds.removeFirst();
        }

        if (hasRepeatedNoProgressRounds()) {
            stagnant = true;
            stagnationDetail = "连续 " + stagnationWindow + " 轮调用未产生新结果";
            return;
        }

        for (int period = 2; period <= MAX_CYCLE_PERIOD; period++) {
            if (hasRepeatedCycle(period)) {
                stagnant = true;
                stagnationDetail = "检测到长度为 " + period + " 的周期调用且结果无进展";
                return;
            }
        }
    }

    public ExitReason check() {
        if (stagnant) {
            return ExitReason.STAGNATION_DETECTED;
        }
        if (totalInputTokens + totalOutputTokens >= tokenBudget) {
            return ExitReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (iteration >= hardMaxIterations) {
            return ExitReason.HARD_ITERATION_LIMIT;
        }
        return ExitReason.WITHIN_BUDGET;
    }

    public int iteration() {
        return iteration;
    }

    public int totalInputTokens() {
        return totalInputTokens;
    }

    public int totalOutputTokens() {
        return totalOutputTokens;
    }

    public int totalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public int hardMaxIterations() {
        return hardMaxIterations;
    }

    public int stagnationWindow() {
        return stagnationWindow;
    }

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case TOKEN_BUDGET_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾",
                    totalInputTokens + totalOutputTokens, tokenBudget);
            case STAGNATION_DETECTED -> "检测到工具调用停滞（"
                    + (stagnationDetail.isBlank() ? "重复调用且没有有效进展" : stagnationDetail)
                    + "），疑似死循环，已强制收尾";
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
        };
    }

    private boolean hasRepeatedNoProgressRounds() {
        if (recentToolRounds.size() < stagnationWindow) {
            return false;
        }
        List<ToolRound> rounds = new ArrayList<>(recentToolRounds);
        int start = rounds.size() - stagnationWindow;
        ToolRound first = rounds.get(start);
        for (int i = start + 1; i < rounds.size(); i++) {
            if (!sameEffectiveRound(first, rounds.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRepeatedCycle(int period) {
        int required = period * 2;
        if (recentToolRounds.size() < required) {
            return false;
        }
        List<ToolRound> rounds = new ArrayList<>(recentToolRounds);
        int start = rounds.size() - required;
        for (int i = 0; i < period; i++) {
            if (!sameEffectiveRound(rounds.get(start + i), rounds.get(start + period + i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameEffectiveRound(ToolRound left, ToolRound right) {
        boolean exactCalls = left.exactSignature().equals(right.exactSignature());
        boolean sameCoreIntent = left.coreSignature().equals(right.coreSignature());
        if (!exactCalls && !sameCoreIntent) {
            return false;
        }

        if (!left.resultsAvailable() || !right.resultsAvailable()) {
            // 没有工具结果时只能安全识别精确调用重复，不能把参数变化猜成"无意义"。
            return exactCalls;
        }
        // 结果判断故意保持保守：只有规范化后完全等价才算无进展。
        // 不能仅凭高相似度判停，否则 100 条结果新增 1 条也可能被误杀。
        return left.normalizedResults().equals(right.normalizedResults());
    }

    private static String signatureOf(List<LlmClient.ToolCall> toolCalls, boolean omitResultWindowArguments) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.ToolCall tc : toolCalls) {
            String name = tc == null || tc.function() == null ? "" : tc.function().name();
            String arguments = tc == null || tc.function() == null ? "" : tc.function().arguments();
            String canonicalArguments = canonicalizeArguments(arguments, omitResultWindowArguments);
            sb.append(name).append('|').append(canonicalArguments).append(';');
        }
        return sb.toString();
    }

    private static String canonicalizeArguments(String raw, boolean omitResultWindowArguments) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            JsonNode normalized = normalizeJson(parsed, omitResultWindowArguments);
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return raw.trim().replaceAll("\\s+", " ");
        }
    }

    private static JsonNode normalizeJson(JsonNode node, boolean omitResultWindowArguments) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                array.add(normalizeJson(child, omitResultWindowArguments));
            }
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = MAPPER.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                if (omitResultWindowArguments && RESULT_WINDOW_ARGUMENTS.contains(fieldName)) {
                    continue;
                }
                object.set(fieldName, normalizeJson(node.get(fieldName), omitResultWindowArguments));
            }
            return object;
        }
        return node;
    }

    private record ToolRound(
            String exactSignature,
            String coreSignature,
            List<String> normalizedResults,
            boolean resultsAvailable
    ) {
        private static ToolRound from(List<LlmClient.ToolCall> calls, List<String> results) {
            boolean available = results != null && results.size() == calls.size();
            List<String> normalized = available
                    ? results.stream().map(ToolRound::normalizeResult).toList()
                    : List.of();
            return new ToolRound(
                    signatureOf(calls, false),
                    signatureOf(calls, true),
                    normalized,
                    available
            );
        }

        private static String normalizeResult(String value) {
            String normalized = value == null ? "" : value.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
                    .replaceAll("[ \\t]+", " ");
            if (normalized.isBlank()) {
                return normalized;
            }
            try {
                JsonNode parsed = MAPPER.readTree(normalized);
                return MAPPER.writeValueAsString(normalizeJson(parsed, false));
            } catch (Exception ignored) {
                return normalized;
            }
        }
    }

    private static int readIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
