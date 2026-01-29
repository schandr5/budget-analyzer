package com.example.demo.service;

import com.example.demo.constants.Constants;
import com.example.demo.dto.GeneratedSqlContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Service
@Slf4j
public class InsightsGenerationService {
    private final ResourceLoader resourceLoader;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String deepseekModel;
    private final String ollamaGenerateUrl;
    private String cachedInsightsGeneratorRules;

    @Autowired
    public InsightsGenerationService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${ollama.deepseek.model:deepseek-r1:7b}") String deepseekModel,
            @Value("${ollama.generate.url:http://localhost:11434/api/generate}") String ollamaGenerateUrl
    ) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.deepseekModel = deepseekModel;
        this.ollamaGenerateUrl = ollamaGenerateUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        this.restTemplate = new RestTemplate(requestFactory);

        ensureInsightsGeneratorRulesLoaded();
    }

    public String generateInsights(GeneratedSqlContext generatedSqlContext) {
        ensureInsightsGeneratorRulesLoaded();
        String finalPrompt = buildInsightsPrompt(generatedSqlContext);
        String insights = generateInsightsWithDeepseek(finalPrompt);
        return insights;
    }

    // Method to build the prompt for the insights model.
    private String buildInsightsPrompt(GeneratedSqlContext generatedSqlContext) {
        if (generatedSqlContext == null) {
            throw new IllegalArgumentException("GeneratedSqlContext is null");
        }

        String originalPrompt = generatedSqlContext.getOriginalPrompt();
        if (originalPrompt == null || originalPrompt.isBlank()) {
            throw new IllegalArgumentException("originalPrompt is null/blank");
        }
        if (generatedSqlContext.getResultSet() == null) {
            throw new IllegalArgumentException("resultSet is null");
        }

        String resultSetJson;
        try {
            resultSetJson = objectMapper.writeValueAsString(generatedSqlContext.getResultSet());
        } catch (Exception e) {
            throw new RuntimeException("Unable to serialize resultSet to JSON", e);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### Instruction\n").append(cachedInsightsGeneratorRules).append('\n');

        sb.append("\n### Input\n");
        sb.append("originalPrompt: ").append(originalPrompt).append('\n');

        if (generatedSqlContext.getRequiredCategories() != null && !generatedSqlContext.getRequiredCategories().isEmpty()) {
            sb.append("requiredCategories: ").append(generatedSqlContext.getRequiredCategories()).append('\n');
        } else if (generatedSqlContext.getRequiredCategory() != null) {
            sb.append("requiredCategory: ").append(generatedSqlContext.getRequiredCategory()).append('\n');
        }
        if (generatedSqlContext.getRequiredMonth() != null) {
            sb.append("requiredMonth: ").append(generatedSqlContext.getRequiredMonth()).append('\n');
        }
        if (generatedSqlContext.getRequiredYear() != null) {
            sb.append("requiredYear: ").append(generatedSqlContext.getRequiredYear()).append('\n');
        }

        sb.append("resultSet: ").append(resultSetJson).append('\n');
        sb.append("note: If the resultSet does not include a 'month' column, treat the totals as aggregated across the requested period (even if the prompt mentions multiple months)—do not assume data is missing.\n");

        sb.append("\n### Response\n");
        return sb.toString();
    }

    // Method to generate insights with the help of the DeepSeek model.
    private String generateInsightsWithDeepseek(String finalPrompt) {
        String url = ollamaGenerateUrl;
        String promptToSend = finalPrompt;
        String lastModelText = "";
        String lastFailureReason = "";

        for (int attempt = 1; attempt <= Constants.OLLAMA_MAX_RETRIES; attempt++) {
            try {
                // Setting the options for the request.
                // Temperature 0 ensures deterministic output.
                // Num_predict 512 ensures the output is not too long.
                    Map<String, Object> options = Map.of(
                            "temperature", 0,
                            "num_predict", 512
                    );
                OllamaGenerateRequest request = new OllamaGenerateRequest(deepseekModel, promptToSend, false, options);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<OllamaGenerateRequest> entity = new HttpEntity<>(request, headers);

                log.info("Calling Ollama DeepSeek (model={}). Attempt={}. Prompt chars={}", deepseekModel, attempt,
                        promptToSend == null ? 0 : promptToSend.length());

                String rawJson = restTemplate.postForObject(url, entity, String.class);
                if (rawJson == null || rawJson.isBlank()) {
                    lastFailureReason = "Empty HTTP response body from Ollama";
                } else {
                    JsonNode root = objectMapper.readTree(rawJson);
                    JsonNode responseText = root.get("response");
                    String modelText = (responseText == null || responseText.isNull()) ? "" : responseText.asText("");
                    lastModelText = modelText;

                    String cleaned = modelText.trim();
                    if (!cleaned.isEmpty()) {
                        return cleaned;
                    }
                    lastFailureReason = "Model returned empty response";
                }
            } catch (RestClientException ex) {
                throw new RuntimeException("Failed to call Ollama at " + url + ". Is Ollama running?", ex);
            } catch (Exception ex) {
                lastFailureReason = "Failed to parse Ollama response: " + ex.getMessage();
            }

            // Retry hint: keep rules identical, just re-nudge output format.
            promptToSend = finalPrompt
                    + "\n\nIMPORTANT: Return ONLY the final insight text (3–4 sentences). No markdown. No SQL. No raw rows. End with a complete sentence, even if it escapes the capacity limit.\n";
        }

        throw new RuntimeException("DeepSeek failed to produce insights after retries. Last failure: "
                + lastFailureReason + ". Last output: " + (lastModelText == null ? "<null>" : lastModelText));
    }

    private record OllamaGenerateRequest(
            String model,
            String prompt,
            boolean stream,
            Map<String, Object> options
    ) {}

    private void ensureInsightsGeneratorRulesLoaded() {
        if (cachedInsightsGeneratorRules != null) {
            return;
        }
        Resource resource = resourceLoader.getResource("classpath:ai/insightsGenerator-rules.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/insightsGenerator-rules.txt");
        }
        cachedInsightsGeneratorRules = readResourceToString(resource);
    }

    // Helper method to read the contents of the resource files.
    private String readResourceToString(Resource resource) {
        if (resource == null) {
            throw new IllegalStateException("Resource is null");
        }

        try (InputStream inputStream = resource.getInputStream();
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while((line = bufferedReader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Unable to read resource: " + resource.getDescription(), e);
        }
    }




}