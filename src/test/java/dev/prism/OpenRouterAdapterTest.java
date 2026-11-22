package dev.prism;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterAdapterTest {
  @Test void sendsBearerKeyToOpenAiCompatibleEndpointAndReadsActualCost() throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/chat/completions",exchange->{
      assertEquals("Bearer unit-test-key",exchange.getRequestHeaders().getFirst("Authorization"));
      assertTrue(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).contains("test/model"));
      byte[] body="{\"id\":\"gen-local\",\"model\":\"test/model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello from OpenRouter\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4,\"total_tokens\":15,\"cost\":0.00125}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try{
      OpenRouterProviderAdapter adapter=new OpenRouterProviderAdapter();
      ReflectionTestUtils.setField(adapter,"baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1");
      ReflectionTestUtils.setField(adapter,"apiKey","unit-test-key");
      var result=adapter.complete("test/model",List.of(Map.of("role","user","content","hi")),64,null);
      assertEquals("hello from OpenRouter",result.text());assertEquals(11,result.promptTokens());assertEquals(.00125,result.cost());assertEquals("gen-local",result.id());
    }finally{server.stop(0);}
  }
}
