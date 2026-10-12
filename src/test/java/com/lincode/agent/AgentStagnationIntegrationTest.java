package com.lincode.agent;

import com.lincode.llm.LlmClient;
import com.lincode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStagnationIntegrationTest {

    @Test
    void reactStopsAfterTwoStepCycleWithoutRequestingAnotherModelRound() {
        ScriptedClient client = new ScriptedClient(List.of(
                toolResponse("call_1", "read_file", "{\"path\":\"Agent.java\"}"),
                toolResponse("call_2", "grep_code", "{\"query\":\"executeTools\"}"),
                toolResponse("call_3", "read_file", "{\"path\":\"Agent.java\"}"),
                toolResponse("call_4", "grep_code", "{\"query\":\"executeTools\"}")
        ));
        Agent agent = new Agent(client, new StableResultToolRegistry());

        String result = agent.run("检查工具调用循环");

        assertEquals(4, client.callCount());
        assertTrue(result.contains("工具调用停滞"));
        assertTrue(result.contains("周期调用"));
    }

    @Test
    void reactStopsWhenOnlyResultWindowChangesAndOutputDoesNotProgress() {
        ScriptedClient client = new ScriptedClient(List.of(
                toolResponse("call_1", "grep_code", "{\"query\":\"login\",\"max_results\":100}"),
                toolResponse("call_2", "grep_code", "{\"query\":\"login\",\"max_results\":101}"),
                toolResponse("call_3", "grep_code", "{\"query\":\"login\",\"max_results\":102}")
        ));
        Agent agent = new Agent(client, new StableResultToolRegistry());

        String result = agent.run("查找登录实现");

        assertEquals(3, client.callCount());
        assertTrue(result.contains("工具调用停滞"));
        assertTrue(result.contains("连续 3 轮"));
    }

    private static LlmClient.ChatResponse toolResponse(String id, String name, String arguments) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                id,
                new LlmClient.ToolCall.Function(name, arguments)
        );
        return new LlmClient.ChatResponse("assistant", "", List.of(call), 10, 2);
    }

    private static final class StableResultToolRegistry extends ToolRegistry {
        @Override
        public String executeTool(String name, String argumentsJson) {
            return "stable result for " + name;
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private int callCount;

        private ScriptedClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            callCount++;
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("不应继续请求下一轮模型响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        private int callCount() {
            return callCount;
        }
    }
}
