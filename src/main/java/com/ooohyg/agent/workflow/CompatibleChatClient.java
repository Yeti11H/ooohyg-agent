package com.ooohyg.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.ToolCall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Chat Completions adapter for a fixture run explicitly opted into by the operator. */
final class CompatibleChatClient implements LlmClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;
    private final String key;
    private final String model;

    CompatibleChatClient(String model) {
        String url = System.getenv("WORKFLOW_CHAT_URL");
        key = System.getenv("WORKFLOW_CHAT_KEY");
        if (url == null || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Set WORKFLOW_CHAT_URL and WORKFLOW_CHAT_KEY");
        }
        endpoint = URI.create(url);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("WORKFLOW_CHAT_URL must be a plain HTTPS Chat Completions endpoint");
        }
        this.model = model;
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        try {
            ObjectNode body = WorkflowFiles.JSON.createObjectNode();
            body.put("model", model);
            ArrayNode history = body.putArray("messages");
            for (Message message : messages) {
                ObjectNode item = history.addObject();
                item.put("role", message.role().name().toLowerCase(Locale.ROOT));
                item.put("content", message.content());
                if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    var calls = item.putArray("tool_calls");
                    for (ToolCall call : message.toolCalls()) {
                        var entry = calls.addObject().put("id", call.id()).put("type", "function");
                        entry.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                    }
                }
            }
            if (tools != null && !tools.isEmpty()) {
                var definitions = body.putArray("tools");
                for (ToolDefinition tool : tools) {
                    var function = definitions.addObject().put("type", "function").putObject("function");
                    function.put("name", tool.name()).put("description", tool.description());
                    function.set("parameters", WorkflowFiles.JSON.readTree(tool.parametersSchema()));
                }
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(WorkflowFiles.JSON.writeValueAsBytes(body))).build();
            var payload = new ByteArrayOutputStream();
            var pending = client.sendAsync(request, info -> HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk ->
                    chunk.ifPresent(bytes -> {
                        if (payload.size() + bytes.length > 512 * 1024) {
                            throw new IllegalStateException("Model response exceeded size limit");
                        }
                        payload.writeBytes(bytes);
                    })));
            try {
                var response = pending.get(45, TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw new IllegalStateException("Model HTTP status " + response.statusCode());
            } finally {
                pending.cancel(true);
            }
            JsonNode reply = WorkflowFiles.JSON.readTree(payload.toByteArray());
            if (reply == null) throw new IllegalStateException("Empty model response");
            JsonNode choice = reply.path("choices").path(0);
            JsonNode message = choice.path("message");
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode function = call.path("function");
                calls.add(new ToolCall(call.path("id").asText(), function.path("name").asText(),
                        function.path("arguments").asText()));
            }
            return new LlmResponse(message.path("content").asText(null), calls,
                    choice.path("finish_reason").asText("unknown"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model request interrupted");
        } catch (IOException | IllegalArgumentException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Model request timed out, failed, or returned invalid data");
        }
    }
}
