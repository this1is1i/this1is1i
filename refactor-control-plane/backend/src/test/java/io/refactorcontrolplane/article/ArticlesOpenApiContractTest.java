package io.refactorcontrolplane.article;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.mcp.McpToolSchemas;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@SpringBootTest
@AutoConfigureMockMvc
class ArticlesOpenApiContractTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test void articleCommandsAndResponsesMatchTheVersionedDocument() throws Exception {
        JsonNode spec = specification();
        var commands = spec.at("/components/schemas/ArticleCommand");
        var response = spec.at("/components/schemas/Article");
        for (ArticleStatus state : ArticleStatus.values()) {
            JsonNode command = mapper.valueToTree(new ArticleCommand("契约文章", "<p>unchanged content</p>", state));
            McpToolSchemas.validate(commands, command, "command");
            var created = mvc.perform(post("/api/articles").contentType(MediaType.APPLICATION_JSON).content(command.toString()))
                    .andReturn().getResponse();
            assertEquals(201, created.getStatus());
            JsonNode body = mapper.readTree(created.getContentAsString());
            McpToolSchemas.validate(response, body, "article");
            assertDoesNotThrow(() -> java.time.Instant.parse(body.path("updatedAt").asText()));
            String location = created.getHeader("Location");
            var fetched = mvc.perform(get(location)).andReturn().getResponse();
            assertEquals(200, fetched.getStatus());
            assertEquals(body, mapper.readTree(fetched.getContentAsString()));
            assertEquals(204, mvc.perform(delete(location)).andReturn().getResponse().getStatus());
        }
    }

    @Test void allReferencesResolveAndAllSuccessfulJsonOperationsDeclareBodies() throws Exception {
        JsonNode spec = specification();
        checkReferences(spec, spec);
        spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            if (!operation.getValue().has("operationId")) return;
            var responses = operation.getValue().path("responses");
            responses.properties().forEach(response -> {
                if (response.getKey().equals("200") || response.getKey().equals("201")) {
                    JsonNode resolved = response.getValue().has("$ref") ? spec.at(response.getValue().path("$ref").asText().substring(1)) : response.getValue();
                    assertTrue(resolved.at("/content/application~1json/schema").isObject(), path.getKey());
                }
            });
        }));
    }
    @Test void openApiPatternsAreUsableByJavaScriptClients() throws Exception {
        Path file = temporary.resolve("openapi.json");
        Files.writeString(file, specification().toString(), StandardCharsets.UTF_8);
        var process = new ProcessBuilder("node", "../scripts/validate-schema-patterns.mjs", file.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
    private JsonNode specification() throws Exception {
        var options = new LoaderOptions(); options.setMaxAliasesForCollections(0);
        return mapper.valueToTree(new Yaml(new SafeConstructor(options)).load(
                Files.readString(Path.of("../docs/contracts/articles.openapi.yaml"), StandardCharsets.UTF_8)));
    }
    private void checkReferences(JsonNode node, JsonNode root) {
        if (node.has("$ref")) assertFalse(root.at(node.path("$ref").asText().substring(1)).isMissingNode(), node.toString());
        if (node.isContainerNode()) node.forEach(child -> checkReferences(child, root));
    }
}
