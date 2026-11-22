package dev.prism;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class OpenRouterProviderAdapter implements ProviderAdapter {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${prism.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl = "https://openrouter.ai/api/v1";
    @Value("${OPENROUTER_API_KEY:}")
    private String apiKey = "";
    @Value("${prism.openrouter.timeout-seconds:60}")
    private int timeoutSeconds = 60;
    @Value("${prism.openrouter.referer:http://localhost:8080}")
    private String referer = "http://localhost:8080";
    @Value("${prism.openrouter.app-title:Prism LLM Gateway}")
    private String appTitle = "Prism LLM Gateway";

    @Override
    public String name() {
        return "openrouter";
    }

    boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    Result complete(String model, List<Map<String, Object>> messages, Integer maxTokens, Double temperature) {
        try {
            Map<String, Object> payload = payload(model, messages, maxTokens, temperature, false);
            HttpResponse<String> response = http.send(request(payload, false).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw upstream(response.statusCode(), response.body());
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            if (root.has("error"))
                throw new UpstreamException(502, root.path("error").path("message").asText("OpenRouter completion failed"));
            if (choice.isMissingNode() || choice.path("message").isMissingNode())
                throw new UpstreamException(502, "OpenRouter returned no completion choice");
            if (choice.has("error") || choice.path("finish_reason").asText().equals("error"))
                throw new UpstreamException(502, choice.path("error").path("message").asText("OpenRouter completion failed"));
            JsonNode usage = root.path("usage");
            return new Result(root.path("id").asText(""), root.path("model").asText(model), content(choice.path("message").path("content")), usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), number(usage.path("cost")), choice.path("finish_reason").asText("stop"));
        } catch (UpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("OpenRouter request interrupted", e);
        } catch (Exception e) {
            throw new UpstreamException("OpenRouter request failed: " + safeMessage(e), e);
        }
    }

    StreamResponse openStream(String model, List<Map<String, Object>> messages, Integer maxTokens, Double temperature) {
        try {
            Map<String, Object> payload = payload(model, messages, maxTokens, temperature, true);
            HttpResponse<InputStream> response = http.send(request(payload, true).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body().readAllBytes());
                throw upstream(response.statusCode(), body);
            }
            return new StreamResponse(response, model);
        } catch (UpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("OpenRouter stream interrupted", e);
        } catch (Exception e) {
            throw new UpstreamException("Could not open OpenRouter stream: " + safeMessage(e), e);
        }
    }

    Result parseStreamUsage(String json, String requestedModel) {
        try {
            JsonNode root = mapper.readTree(json), usage = root.path("usage"), choice = root.path("choices").path(0);
            String model = root.path("model").asText(requestedModel);
            return new Result(root.path("id").asText(""), model, "", usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), number(usage.path("cost")), choice.path("finish_reason").asText("stop"));
        } catch (Exception e) {
            return new Result("", requestedModel, "", 0, 0, null, "stop");
        }
    }

    String streamChunkContent(String json) {
        try {
            JsonNode choice = mapper.readTree(json).path("choices").path(0);
            JsonNode content = choice.path("delta").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    String streamError(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("error")) return root.path("error").path("message").asText("OpenRouter stream failed");
            JsonNode choice = root.path("choices").path(0);
            if (choice.has("error") || choice.path("finish_reason").asText().equals("error"))
                return choice.path("error").path("message").asText("OpenRouter stream failed");
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> payload(String model, List<Map<String, Object>> messages, Integer maxTokens, Double temperature, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", stream);
        if (maxTokens != null && maxTokens > 0) body.put("max_tokens", maxTokens);
        if (temperature != null) body.put("temperature", temperature);
        if (stream) body.put("stream_options", Map.of("include_usage", true));
        return body;
    }

    private HttpRequest.Builder request(Map<String, Object> payload, boolean stream) throws IOException {
        if (!configured()) throw new UpstreamException(503, "OPENROUTER_API_KEY is not configured");
        String uri = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("HTTP-Referer", referer).header("X-Title", appTitle)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
    }

    private UpstreamException upstream(int status, String body) {
        String message = "OpenRouter returned HTTP " + status;
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("error")) {
                JsonNode err = node.path("error");
                message += " - " + err.path("message").asText("request rejected");
            }
        } catch (Exception ignored) {
        }
        return new UpstreamException(status, message);
    }

    private String content(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringBuilder s = new StringBuilder();
            for (JsonNode part : node)
                if (part.path("type").asText().equals("text")) s.append(part.path("text").asText());
            return s.toString();
        }
        return "";
    }

    private Double number(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    record Result(String id, String model, String text, int promptTokens, int completionTokens, Double cost,
                  String finishReason) {
    }

    record StreamResponse(HttpResponse<InputStream> response, String requestedModel) {
    }

    static final class UpstreamException extends RuntimeException {
        final int status;

        UpstreamException(int status, String message) {
            super(message);
            this.status = status;
        }

        UpstreamException(String message, Throwable cause) {
            super(message, cause);
            this.status = 502;
        }
    }
}
