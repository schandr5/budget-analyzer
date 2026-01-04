package com.example.demo.service;

import com.example.demo.constants.Constants;
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
import com.example.demo.model.Budget;
import com.example.demo.repository.BudgetRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

@Service
@Slf4j
public class TextToSqlGenerationService {

    private final ResourceLoader resourceLoader;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String ollamaModel;
    private final BudgetRepository budgetRepository;

    private String cachedSqlCoderRules;
    private String cachedSqlCoderSchema;
    private List<String> cachedTransactionCategories;

    private static final Set<String> BANNED_SQL_TOKENS = Constants.SQL_BANNED_TOKENS;

    // Minimal DTOs for Ollama's /api/generate endpoint
    private record OllamaGenerateRequest(String model, String prompt, boolean stream, Map<String, Object> options) {}

    @Autowired
    public TextToSqlGenerationService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            BudgetRepository budgetRepository,
            @Value("${ollama.model:sqlcoder:latest}") String ollamaModel
    ) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.budgetRepository = budgetRepository;
        this.ollamaModel = ollamaModel;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        this.restTemplate = new RestTemplate(requestFactory);

        ensureSqlCoderTemplatesLoaded();
    }


    public String generateSql(Long budgetId, String prompt) {
        ensureSqlCoderTemplatesLoaded();
        String requiredCategory = detectCategory(prompt);
        Integer requiredMonth = detectMonth(prompt);
        Integer inferredYear = inferYearFromBudget(budgetId, requiredMonth);
        String finalPrompt = buildSqlCoderPrompt(budgetId, prompt, requiredCategory, requiredMonth, inferredYear);
        return generateSqlWithOllama(finalPrompt);
    }

    private String generateSqlWithOllama(String finalPrompt) {
        String url = Constants.OLLAMA_GENERATE_URL;

        String promptToSend = finalPrompt;
        String lastModelText = "";
        String lastFailureReason = "";

        for (int attempt = 1; attempt <= Constants.OLLAMA_MAX_RETRIES; attempt++) {
            Map<String, Object> options = Map.of(
                    "temperature", 0, // deterministic
                    "num_predict", 256 // cap output length
            );

            OllamaGenerateRequest request = new OllamaGenerateRequest(ollamaModel, promptToSend, false, options);

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<OllamaGenerateRequest> entity = new HttpEntity<>(request, headers);

                log.info("Calling Ollama (model={}). Attempt={}. Prompt chars={}", ollamaModel, attempt, promptToSend == null ? 0 : promptToSend.length());

                String rawJson = restTemplate.postForObject(url, entity, String.class);
                if (rawJson == null || rawJson.isBlank()) {
                    throw new RuntimeException("Empty response from Ollama");
                }

                JsonNode root = objectMapper.readTree(rawJson);
                String modelText = root.path("response").asText("").trim();
                lastModelText = modelText;

                String sql = null;
                try {
                    sql = extractSql(modelText);
                    lastFailureReason = "";
                } catch (RuntimeException ex) {
                    lastFailureReason = ex.getMessage();
                }

                boolean banned = (sql == null) || containsBannedTokens(sql);
                long requiredBudgetId = attemptBudgetIdFromPrompt(promptToSend);
                boolean missingBudgetFilter = (sql == null) || !containsRequiredBudgetId(sql, requiredBudgetId);
                String requiredCategory = attemptCategoryFromPrompt(promptToSend);
                boolean missingCategoryFilter = (requiredCategory != null) && ((sql == null) || !containsRequiredCategory(sql, requiredCategory));
                Integer requiredMonth = attemptMonthFromPrompt(promptToSend);
                boolean missingMonthFilter = (requiredMonth != null) && ((sql == null) || !containsRequiredMonth(sql, requiredMonth));
                Integer requiredYear = attemptYearFromPrompt(promptToSend);
                boolean missingYearFilter = (requiredYear != null) && ((sql == null) || !containsRequiredYear(sql, requiredYear));

                if (!banned && !missingBudgetFilter && !missingCategoryFilter && !missingMonthFilter && !missingYearFilter) {
                    return sql;
                }

                // Correct the prompt and retry the model
                promptToSend = finalPrompt + buildRetryHint(
                        lastFailureReason,
                        banned,
                        missingBudgetFilter,
                        requiredBudgetId,
                        missingCategoryFilter,
                        requiredCategory,
                        lastModelText,
                        missingMonthFilter,
                        requiredMonth,
                        missingYearFilter,
                        requiredYear
                );
            } catch (RestClientException ex) {
                throw new RuntimeException("Failed to call Ollama at " + url + ". Is Ollama running?", ex);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to parse Ollama response", ex);
            }
        }

        throw new RuntimeException(
                "Model failed to produce safe MySQL SQL after retries. "
                        + "Last output: " + (lastModelText == null ? "<null>" : lastModelText)
        );
    }

    private String buildRetryHint(
            String lastFailureReason,
            boolean banned,
            boolean missingBudgetFilter,
            long requiredBudgetId,
            boolean missingCategoryFilter,
            String requiredCategory,
            String lastModelText,
            boolean missingMonthFilter,
            Integer requiredMonth,
            boolean missingYearFilter,
            Integer requiredYear
    ) {
        StringBuilder retryHint = new StringBuilder("\n\n### Correction\n");
        retryHint.append("Your previous output was invalid.\n");
        if (lastFailureReason != null && !lastFailureReason.isBlank()) {
            retryHint.append("Reason: ").append(lastFailureReason).append("\n");
        }
        retryHint.append("Rewrite the query in MySQL 8.x syntax ONLY and output ONLY the corrected SQL.\n");
        retryHint.append("The corrected SQL MUST start with SELECT.\n");
        if (banned) {
            retryHint.append("- It used PostgreSQL-only syntax (for example ILIKE / interval '...').\n");
            retryHint.append("  Use MySQL date math like: DATE_SUB(CURDATE(), INTERVAL 3 MONTH) (never interval '3 month').\n");
        }
        if (missingBudgetFilter) {
            retryHint.append("- It did NOT include the required filter: budget_id = ")
                    .append(requiredBudgetId)
                    .append("\n");
        }
        if (missingCategoryFilter) {
            retryHint.append("- It did NOT include the required filter: category = '")
                    .append(requiredCategory)
                    .append("'\n");
        }
        if (missingMonthFilter && requiredMonth != null) {
            retryHint.append("- It did NOT include the required month filter: MONTH(time_stamp) = ")
                    .append(requiredMonth)
                    .append("\n");
        }
        if (missingYearFilter && requiredYear != null) {
            retryHint.append("- It did NOT include the required year filter: YEAR(time_stamp) = ")
                    .append(requiredYear)
                    .append("\n");
        }
        retryHint.append("\nPrevious output:\n")
                .append(lastModelText == null || lastModelText.isBlank() ? "<empty>" : lastModelText)
                .append("\n\nCorrected SQL:\n");
        return retryHint.toString();
    }

    private String buildSqlCoderPrompt(Long budgetId, String prompt, String requiredCategory, Integer requiredMonth, Integer requiredYear) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Instruction\n").append(cachedSqlCoderRules)
                .append("\n\n### Input\n")
                .append("Schema:\n").append(cachedSqlCoderSchema)
                .append("\n\nConstraints:\n")
                .append("- budget_id must be ").append(budgetId).append('\n');
        if (requiredCategory != null) {
            sb.append("- category must be '").append(requiredCategory).append("'\n");
        }
        if (requiredMonth != null) {
            sb.append("- month must be ").append(requiredMonth).append(" (use MONTH(time_stamp) = ").append(requiredMonth).append(")\n");
        }
        if (requiredYear != null) {
            sb.append("- year must be ").append(requiredYear).append(" (use YEAR(time_stamp) = ").append(requiredYear).append(")\n");
        }
        sb.append("\nQuestion:\n").append(prompt)
                .append("\n\n### Response\n");
        return sb.toString();
    }

    private String extractSql(String modelText) {
        if (modelText == null) {
            throw new RuntimeException("Model returned null text");
        }
        String trimmed = modelText.trim();
        Matcher match = Constants.SQL_SELECT_PATTERN.matcher(trimmed);
        if (!match.find()) {
            if (looksLikeSelectBody(trimmed)) {
                trimmed = "SELECT " + trimmed;
                match = Constants.SQL_SELECT_PATTERN.matcher(trimmed);
                if (!match.find()) {
                    throw new RuntimeException("Model did not return SQL starting with SELECT. Got: " + modelText.trim());
                }
            } else {
                throw new RuntimeException("Model did not return SQL starting with SELECT. Got: " + trimmed);
            }
        }
        String sql = match.group(0).trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    private boolean looksLikeSelectBody(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim();
        if (!lower.contains(" from ")) return false;
        return lower.startsWith("sum(")
                || lower.startsWith("count(")
                || lower.startsWith("avg(")
                || lower.startsWith("min(")
                || lower.startsWith("max(")
                || lower.startsWith("distinct ")
                || lower.startsWith("case ");
    }

    private boolean containsBannedTokens(String sql) {
        if (sql == null) return true;
        String s = " " + sql.toLowerCase(Locale.ROOT) + " ";
        for (String token : BANNED_SQL_TOKENS) {
            if (s.contains(token)) return true;
        }
        return false;
    }

    private boolean containsRequiredBudgetId(String sql, long requiredBudgetId) {
        if (sql == null) return false;
        Matcher m = Constants.SQL_BUDGET_ID_EQUALS_PATTERN.matcher(sql);
        while (m.find()) {
            try {
                long found = Long.parseLong(m.group(1));
                if (found == requiredBudgetId) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // keep scanning
            }
        }
        return false;
    }

    private long attemptBudgetIdFromPrompt(String prompt) {
        if (prompt == null) return -1;
        Matcher m = Constants.PROMPT_REQUIRED_BUDGET_ID_PATTERN.matcher(prompt);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        Matcher m2 = Constants.PROMPT_BUDGET_ID_FALLBACK_PATTERN.matcher(prompt);
        if (m2.find()) {
            return Long.parseLong(m2.group(1));
        }
        return -1;
    }

    private String attemptCategoryFromPrompt(String prompt) {
        if (prompt == null) return null;
        Matcher matcher = Constants.PROMPT_REQUIRED_CATEGORY_PATTERN.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Integer attemptMonthFromPrompt(String prompt) {
        if (prompt == null) return null;
        Matcher matcher = java.util.regex.Pattern.compile("(?m)^- month must be (\\d+)\\b").matcher(prompt);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private Integer attemptYearFromPrompt(String prompt) {
        if (prompt == null) return null;
        Matcher matcher = java.util.regex.Pattern.compile("(?m)^- year must be (\\d{4})\\b").matcher(prompt);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private boolean containsRequiredMonth(String sql, int month) {
        if (sql == null) return false;
        String s = sql.toLowerCase(Locale.ROOT);
        return s.matches("(?s).*month\\s*\\(\\s*time_stamp\\s*\\)\\s*=\\s*" + month + ".*");
    }

    private boolean containsRequiredYear(String sql, int year) {
        if (sql == null) return false;
        String s = sql.toLowerCase(Locale.ROOT);
        return s.matches("(?s).*year\\s*\\(\\s*time_stamp\\s*\\)\\s*=\\s*" + year + ".*");
    }

    private boolean containsRequiredCategory(String sql, String requiredCategory) {
        if (sql == null || requiredCategory == null) return false;
        String needle1 = "category = '" + requiredCategory.replace("'", "''") + "'";
        String needle2 = "transaction.category = '" + requiredCategory.replace("'", "''") + "'";
        String needle3 = "`transaction`.category = '" + requiredCategory.replace("'", "''") + "'";
        String normalizedSql = sql.toLowerCase(Locale.ROOT);
        return normalizedSql.contains(needle1.toLowerCase(Locale.ROOT))
                || normalizedSql.contains(needle2.toLowerCase(Locale.ROOT))
                || normalizedSql.contains(needle3.toLowerCase(Locale.ROOT));
    }

    private String detectCategory(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) return null;
        ensureSqlCoderTemplatesLoaded();
        String prompt = userPrompt.toLowerCase(Locale.ROOT);
        for (String cat : cachedTransactionCategories) {
            if (prompt.contains(cat.toLowerCase(Locale.ROOT))) {
                return cat;
            }
        }
        return null;
    }

    private Integer detectMonth(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) return null;
        String p = userPrompt.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : Constants.MONTH_NAME_TO_NUMBER.entrySet()) {
            if (p.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private Integer inferYearFromBudget(Long budgetId, Integer requiredMonth) {
        if (budgetId == null || requiredMonth == null) return null;
        Budget budget = budgetRepository.findById(budgetId).orElse(null);
        if (budget == null) return null;
        LocalDate start = budget.getStartDate();
        LocalDate end = budget.getEndDate();
        if (start == null || end == null) return null;
        if (start.getYear() == end.getYear()) {
            return start.getYear();
        }
        return null;
    }

    private void ensureSqlCoderTemplatesLoaded() {
        if (cachedSqlCoderRules == null) {
            cachedSqlCoderRules = readResourceToString(loadSQLRules());
        }
        if (cachedSqlCoderSchema == null) {
            cachedSqlCoderSchema = readResourceToString(loadSQLSchema());
        }
        if (cachedTransactionCategories == null) {
            String raw = readResourceToString(loadTransactionCategories());
            cachedTransactionCategories = raw.lines()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
    }

    private String readResourceToString(Resource resource) {
        if (resource == null) {
            throw new IllegalStateException("Resource is null");
        }

        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Unable to read resource: " + resource.getDescription(), e);
        }
    }

    private Resource loadSQLRules() {
        Resource resource = resourceLoader.getResource("classpath:ai/sqlcoder-rules.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/sqlcoder-rules.txt");
        }
        return resource;
    }

    private Resource loadSQLSchema() {
        Resource resource = resourceLoader.getResource("classpath:ai/sqlcoder-schema.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/sqlcoder-schema.txt");
        }
        return resource;
    }

    private Resource loadTransactionCategories() {
        Resource resource = resourceLoader.getResource("classpath:ai/transaction-categories.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/transaction-categories.txt");
        }
        return resource;
    }
}

