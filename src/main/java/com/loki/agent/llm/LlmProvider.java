package com.loki.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loki.agent.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmProvider.class);
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public LlmResponse chat(List<Map<String, Object>> messages,
                             List<Map<String, Object>> tools,
                             String model,
                             int maxTokens) {
        List<Message> springMessages = toSpringMessages(messages);
        Prompt prompt = new Prompt(springMessages);

        try {
            ChatResponse response = chatModel.call(prompt);
            var output = response.getResult().getOutput();

            String content = output.getText();

            List<ToolCall> toolCalls = new ArrayList<>();
            if (output.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                    Map<String, Object> args = parseArgs(tc.arguments());
                    toolCalls.add(new ToolCall(tc.id(), tc.name(), args));
                }
            }

            return new LlmResponse(content, toolCalls, null, Map.of());

        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", arguments);
            return Map.of();
        }
    }

    private List<Message> toSpringMessages(List<Map<String, Object>> messages) {
        List<Message> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            switch (role) {
                case "system" -> result.add(new SystemMessage(content));
                case "user" -> result.add(new UserMessage(content));
                case "assistant" -> result.add(new AssistantMessage(content != null ? content : ""));
                default -> result.add(new UserMessage(content));
            }
        }
        return result;
    }
}
