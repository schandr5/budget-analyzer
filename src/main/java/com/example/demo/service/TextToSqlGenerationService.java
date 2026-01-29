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
import com.example.demo.model.Budget;
import com.example.demo.repository.BudgetRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

@Service
@Slf4j
public class TextToSqlGenerationService {

    private final ResourceLoader resourceLoader;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String ollamaModel;
    private final String ollamaGenerateUrl;
    private final BudgetRepository budgetRepository;

    private String cachedSqlCoderRules;
    private String cachedSqlCoderSchema;
    private List<String> cachedTransactionCategories;
    private List<String> cachedSingleWordCategories;
    private List<String> cachedMultiWordCategories;

    private static final Set<String> BANNED_SQL_TOKENS = Constants.SQL_BANNED_TOKENS;

    // Ollama SQLCoder request
    private record OllamaGenerateRequest(String model, String prompt, boolean stream, Map<String, Object> options) {}
    private record PromptConstraints(
            Long budgetId,
            Long userId,
            String prompt,
            String requiredCategory,
            List<String> requiredCategories,
            Integer requiredMonth,
            Integer requiredYear,
            List<Integer> monthsFound,
            boolean multiMonth,
            boolean multiCategory
    ) {}

    // Validation methods to check the generated SQL against the required filters.
    private static final class SqlValidation {
        private SqlValidation() {}

        // Check if the generated SQL contains any banned tokens.
        static boolean containsBannedTokens(String sql) {
            if (sql == null) return true;
            // Normalize whitespace so we catch tokens even if split by newlines/tabs.
            String normalized = (" " + sql.toLowerCase(Locale.ROOT) + " ").replaceAll("\\s+", " ");
            for (String token : BANNED_SQL_TOKENS) {
                if (normalized.contains(token)) return true;
            }
            // Extra guard for GRANT/REVOKE regardless of spacing/newlines.
            if (normalized.contains(" grant ") || normalized.contains(" revoke ")) return true;
            return false;
        }

        // Require that the transaction table is backticked and aliased as t, and that columns use the alias.
        // Ex: FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id
        static boolean usesTransactionAlias(String sql) {
            if (sql == null) return false;

            // Checks if the SQL contains the accepted pattern of substring that matches FROM `transaction` <alias>
            Matcher transactionAliasMatcher = Constants.SQL_FROM_TRANSACTION_ALIAS_PATTERN.matcher(sql);
            if (!transactionAliasMatcher.find()) {
                return false;
            }

            // Checks if the SQL contains the accepted pattern of substring that matches <alias>
            // Ex: t
            String alias = transactionAliasMatcher.group(1);
            if (!"t".equalsIgnoreCase(alias)) {
                return false;
            }
            
            // Checks if the SQL contains the unaccepted pattern of substring that matches `transaction`.<column>
            // Ex: transaction.<column>
            Matcher bareTransactionDotPatternMatcher = Constants.SQL_BARE_TRANSACTION_DOT_PATTERN.matcher(sql);
            if (bareTransactionDotPatternMatcher.find()) {
                return false;
            }
            return true;
        }

        static boolean containsRequiredBudgetId(String sql, long requiredBudgetId) {
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

        // Check if the generated SQL contains the required user_id.
        // This is essential to ensure that the query is scoped to a specific user.
        static boolean containsRequiredUserId(String sql, long requiredUserId) {
            if (sql == null) return false;

            // Checks if the generated SQL contains the invalid user_id reference on the transaction table.
            if (Constants.SQL_TRANSACTION_USER_ID_PATTERN.matcher(sql).find()) {
                return false;
            }

            // Checks if the generated SQL joins on the budget table.
            // Ex: FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id
            if (!Constants.SQL_JOIN_BUDGET_PATTERN.matcher(sql).find()) {
                return false;
            }

            // We allow `b.user_id = <id>` / `budget.user_id = <id>` ONLY because we already:
            // 1) rejected `t.user_id` / `transaction.user_id` (invalid/unsafe), and
            // 2) required a JOIN on the `budget` table (so the alias/prefix is meaningful).
            Matcher matcher = Constants.SQL_BUDGET_USER_ID_EQUALS_PATTERN.matcher(sql);
            while (matcher.find()) {
                try {
                    long found = Long.parseLong(matcher.group(1));
                    if (found == requiredUserId) return true;
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }
            
            // We allow unqualified `user_id = <id>` as well. This will also match `b.user_id = <id>`,
            // but the earlier checks ensure the query is budget-scoped and not using `transaction.user_id`.
            Matcher unqualified = Constants.SQL_USER_ID_EQUALS_PATTERN.matcher(sql);
            while (unqualified.find()) {
                try {
                    long found = Long.parseLong(unqualified.group(1));
                    if (found == requiredUserId) return true;
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }

            return false;
        }

        // The user prompt may contain a single month. In such cases, we need to check if the generated SQL contains the month filter.
        static boolean containsSingleMonth(String sql, int month) {
            if (sql == null) return false;

            Matcher monthEqualsPatternMatcher = Constants.SQL_MONTH_EQUALS_PATTERN.matcher(sql);
            while (monthEqualsPatternMatcher.find()) {
                try {
                    int monthFound = Integer.parseInt(monthEqualsPatternMatcher.group(1));
                    if (monthFound == month) return true;
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }
            return false;
        }

        // We need the SQL query to be scoped to a specific year, hence we need to check if the generated SQL contains the year filter.
        static boolean containsRequiredYear(String sql, int year) {
            if (sql == null) return false;

            Matcher yearEqualsPatternMatcher = Constants.SQL_YEAR_EQUALS_PATTERN.matcher(sql);
            while (yearEqualsPatternMatcher.find()) {
                try {
                    int yearFound = Integer.parseInt(yearEqualsPatternMatcher.group(1));
                    if (yearFound == year) return true;
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }
            return false;
        }

        // Checks if the generated SQL contains the months mentioned in the user prompt.
        static boolean containsMultiMonth(String sql, List<Integer> months) {
            if (sql == null) return false;

            // In some cases, the user may not have mentioned the months in the prompt.
            if (months == null || months.isEmpty()) return true;

            // Checks if the generated SQL contains the accepted pattern of substring that matches MONTH(time_stamp) IN (<list>)
            // The months list contains the months in the form of integers ex: June maps to 6 and November maps to 11.
            Matcher monthInPatternMatcher = Constants.SQL_MONTH_IN_PATTERN.matcher(sql);
            if (monthInPatternMatcher.find()) {
                String[] monthsInPattern = monthInPatternMatcher.group(1).split(",");
                List<Integer> monthsFound = new ArrayList<>();
                for (String monthInPattern : monthsInPattern) {
                    try {
                        monthsFound.add(Integer.parseInt(monthInPattern.trim()));
                    } catch (NumberFormatException ignored) {
                        // skip invalid entries
                    }
                }
                return monthsFound.containsAll(months);
            }

            // If the model generates MONTH(time_stamp)=6 or MONTH(time_stamp)=6 OR MONTH(time_stamp)=7 instead of IN (6,7)
            List<Integer> monthsMatched = new ArrayList<>();
            Matcher monthEqualsPatternMatcher = Constants.SQL_MONTH_EQUALS_PATTERN.matcher(sql);
            while (monthEqualsPatternMatcher.find()) {
                try {
                    monthsMatched.add(Integer.parseInt(monthEqualsPatternMatcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }
            return monthsMatched.containsAll(months);
        }

        // Check if the generated SQL contains the required category.
        // Ex: transaction.category = 'Utilities'
        static boolean containsRequiredCategory(String sql, String requiredCategory) {
            if (sql == null || requiredCategory == null) return false;

            // Simple substring checks (case-insensitive). We escaped single quotes in the required category.
            String needle1 = "category = '" + requiredCategory.replace("'", "''") + "'";
            String needle2 = "transaction.category = '" + requiredCategory.replace("'", "''") + "'";
            String needle3 = "`transaction`.category = '" + requiredCategory.replace("'", "''") + "'";
            String normalizedSql = sql.toLowerCase(Locale.ROOT);
            return normalizedSql.contains(needle1.toLowerCase(Locale.ROOT))
                    || normalizedSql.contains(needle2.toLowerCase(Locale.ROOT))
                    || normalizedSql.contains(needle3.toLowerCase(Locale.ROOT));
        }

        static boolean containsRequiredCategories(String sql, List<String> requiredCategories) {
            if (requiredCategories == null || requiredCategories.isEmpty()) return true;
            if (sql == null) return false;

            for (String category : requiredCategories) {
                if (category == null || category.isBlank()) continue;
                if (containsCategoryEquals(sql, category)) continue;
                if (containsCategoryIn(sql, category)) continue;
                return false;
            }
            return true;
        }

        private static boolean containsCategoryEquals(String sql, String category) {
            String escaped = category.replace("'", "''");
            String normalized = sql.toLowerCase(Locale.ROOT);
            String c = escaped.toLowerCase(Locale.ROOT);
            // Allow t.category / transaction.category / `transaction`.category / unqualified category
            return normalized.contains(("category = '" + c + "'"))
                    || normalized.contains(("t.category = '" + c + "'"))
                    || normalized.contains(("transaction.category = '" + c + "'"))
                    || normalized.contains(("`transaction`.category = '" + c + "'"));
        }

        private static boolean containsCategoryIn(String sql, String category) {
            // Matches: category IN ('a','b') with optional qualifier.
            Matcher m = java.util.regex.Pattern.compile(
                    "(?is)\\b(?:(?:`?transaction`?|t)\\s*\\.\\s*)?category\\s+in\\s*\\(([^)]*)\\)"
            ).matcher(sql);

            String target = normalizeQuotedLiteral(category);
            while (m.find()) {
                String list = m.group(1);
                for (String raw : list.split(",")) {
                    String v = normalizeQuotedLiteral(raw);
                    if (!v.isEmpty() && v.equals(target)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String normalizeQuotedLiteral(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            // strip surrounding quotes if present
            if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
                s = s.substring(1, s.length() - 1);
            }
            return s.trim().toLowerCase(Locale.ROOT);
        }
    }

    // Helper class to build the required prompt for the SqlCoder model.
    private static class PromptBuilder {
        static String build(PromptConstraints constraints, String rules, String schema) {
            StringBuilder sb = new StringBuilder();
            sb.append("### Instruction\n").append(rules)
                    .append("\n\n### Input\n")
                    .append("Schema:\n").append(schema)
                    .append("\n\nConstraints:\n");

            // We expect the sqlQuery generated by the model to be scoped to a specific user, hence we need to filter by user_id.
            if (constraints.userId() != null) {
                sb.append("- user_id must be ").append(constraints.userId()).append('\n');
            }

            // Enforce strict aliasing and join shape every time.
            sb.append("- You MUST use this FROM/JOIN shape:\n");
            sb.append("  FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id\n");
            sb.append("  WHERE b.user_id = ").append(constraints.userId() != null ? constraints.userId() : "<user_id>").append('\n');
            sb.append("  Use t.<column> for all transaction columns; use b.<column> for budget columns.\n");
            sb.append("  NEVER use transaction.<column> or bare user_id; NEVER use t.user_id (it does not exist).\n");
            sb.append("  Do not alter this join shape.\n");

            // We expect the sqlQuery generated by the model to be scoped to a specificCategory, requiredMonth and requiredYear.
            // If present, we add them to the prompt.
            if (constraints.multiCategory() && constraints.requiredCategories() != null && !constraints.requiredCategories().isEmpty()) {
                String csv = constraints.requiredCategories().stream()
                        .filter(Objects::nonNull)
                        .map(c -> "'" + c.replace("'", "''") + "'")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                sb.append("- categories must be IN (").append(csv).append(") (use t.category IN (").append(csv).append("))\n");
            } else if (constraints.requiredCategory() != null) {
                sb.append("- category must be '").append(constraints.requiredCategory()).append("'\n");
                if (constraints.requiredCategory().contains(" ") || constraints.requiredCategory().contains("&") || constraints.requiredCategory().contains("/")) {
                    sb.append("  IMPORTANT: Do NOT split this category. Use the exact full string inside single quotes.\n");
                }
            }

            if (constraints.requiredMonth() != null) {
                sb.append("- month must be ").append(constraints.requiredMonth()).append(" (use MONTH(time_stamp) = ").append(constraints.requiredMonth()).append(")\n");
            }

            if (constraints.requiredYear() != null) {
                sb.append("- year must be ").append(constraints.requiredYear()).append(" (use YEAR(time_stamp) = ").append(constraints.requiredYear()).append(")\n");
            }

            // If the user is asking for a multi-month query, we need to filter by the months.
            // Ex: How much did I spend on Utilities in the month of May and June ?
            // We need to add May, June to the prompt.
            // We also need to filter by the year if it is provided.
            if (constraints.monthsFound() != null && constraints.monthsFound().size() >= 2) {
                String monthsCsv = constraints.monthsFound().stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .map(String::valueOf)
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                sb.append("- Multi-month comparison requested. Months are: ").append(monthsCsv).append('\n');
                sb.append("  Return one row per month with: MONTH(t.time_stamp) AS month, SUM(t.amount_spend) AS total_spent\n");
                sb.append("  Use GROUP BY MONTH(t.time_stamp) and ORDER BY month.\n");
                sb.append("  Filter months using MONTH(t.time_stamp) IN (").append(monthsCsv).append(").\n");

                // Adding the over-constrained year filter to ensure that the query is scoped to a specific year.
                if (constraints.requiredYear() != null) {
                    sb.append("  Also filter by year using YEAR(t.time_stamp) = ").append(constraints.requiredYear()).append(".\n");
                }

                sb.append("  TEMPLATE: FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id WHERE b.user_id = <user_id>");

                // Adding the over-constrained year filter to ensure that the query is scoped to a specific year.
                if (constraints.requiredYear() != null) {
                    sb.append(" AND YEAR(t.time_stamp) = ").append(constraints.requiredYear());
                }
                sb.append(" AND MONTH(t.time_stamp) IN (").append(monthsCsv).append(")\n");
            }


            // If the user is asking Where did I overspend ? or Which category did I spend the most on ? 
            // we need to return a category breakdown, otherwise the insights model cannot name the category.
            if (constraints.requiredCategory() == null && isTopCategoryQuestion(constraints.prompt())) {
                sb.append("- The query MUST return category and an aggregated spend metric per category.\n");
                sb.append("  Use this template shape (adjust SELECT as needed):\n");
                sb.append("  SELECT t.category, SUM(t.amount_spend) AS total_spent\n");
                sb.append("  FROM `transaction` t\n");
                sb.append("  JOIN budget b ON b.budget_id = t.budget_id\n");
                sb.append("  WHERE b.user_id = <user_id>");
                if (constraints.requiredYear() != null) {
                    sb.append(" AND YEAR(t.time_stamp) = ").append(constraints.requiredYear());
                }
                if (constraints.monthsFound() != null && !constraints.monthsFound().isEmpty()) {
                    if (constraints.multiMonth()) {
                        String monthsCsv = constraints.monthsFound().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                        sb.append(" AND MONTH(t.time_stamp) IN (").append(monthsCsv).append(")");
                    } else {
                        sb.append(" AND MONTH(t.time_stamp) = ").append(constraints.monthsFound().get(0));
                    }
                }
                sb.append("\n");
                sb.append("  GROUP BY t.category ORDER BY total_spent DESC LIMIT 5\n");
            }

            sb.append("\nQuestion:\n").append(constraints.prompt())
                    .append("\n\n### Response\n");
            return sb.toString();
        }
    }

    @Autowired
    public TextToSqlGenerationService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            BudgetRepository budgetRepository,
            @Value("${ollama.model:sqlcoder:latest}") String ollamaModel,
            @Value("${ollama.generate.url:http://localhost:11434/api/generate}") String ollamaGenerateUrl
    ) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.budgetRepository = budgetRepository;
        this.ollamaModel = ollamaModel;
        this.ollamaGenerateUrl = ollamaGenerateUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        this.restTemplate = new RestTemplate(requestFactory);

        // Ensures that the SqlCoder rules, schema, transaction categories are loaded.
        ensureSqlCoderTemplatesLoaded();
    }

    // Main method to generate the SQL query from the user prompt.
    public GeneratedSqlContext generateSql(Long budgetId, String prompt) {

        // All the prompts sent to the model need to have a budgetId.
        // We fetch the userId and other details using the budgetId. This also ensures that the prompt is scoped to a specific user.
        if (budgetId == null) {
            throw new RuntimeException("budgetId is required");
        }
        
        // Check for the initial user prompt.
        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("prompt is required");
        }

        // Helper methods to detect the category and months from the user prompt.
        List<String> requiredCategories = detectCategories(prompt);
        boolean multiCategory = requiredCategories.size() >= 2;
        String requiredCategory = (requiredCategories.size() == 1) ? requiredCategories.get(0) : null;
        List<Integer> monthsFound = detectMonths(prompt);
        
        // Flag to check if the user prompt is for a multi-month query.
        // Ex: How much did I spend on Utilities in the month of May and June ?
        boolean multiMonth = monthsFound.size() >= 2;

        // Check the number of months found in the user prompt.
        Integer requiredMonth = multiMonth ? null : monthsFound.get(0);

        // Helper method to detect the year from the user prompt.
        Integer requiredYear = detectYear(prompt);
        if (requiredYear == null) {
            requiredYear = inferYearFromBudget(budgetId, requiredMonth);
        }

        // Helper method to infer the userId from the budgetId.
        Long inferredUserId = inferUserIdFromBudget(budgetId);
        if (inferredUserId == null) {
            throw new RuntimeException("Unable to resolve user_id from budgetId=" + budgetId);
        }

        PromptConstraints constraints = new PromptConstraints(
                budgetId,
                inferredUserId,
                prompt,
                requiredCategory,
                requiredCategories,
                requiredMonth,
                requiredYear,
                monthsFound,
                multiMonth,
                multiCategory
        );

        // Final prompt is the prompt sent to the model with the constraints and the rules.
        String finalPrompt = PromptBuilder.build(constraints, cachedSqlCoderRules, cachedSqlCoderSchema);
        String sqlQuery = generateSqlWithOllama(finalPrompt, constraints);

        GeneratedSqlContext ctx = new GeneratedSqlContext();
        ctx.setRequiredCategory(requiredCategory);
        ctx.setRequiredCategories(requiredCategories);
        ctx.setRequiredMonth(requiredMonth);
        ctx.setRequiredYear(requiredYear);
        ctx.setSqlQuery(sqlQuery);
        ctx.setOriginalPrompt(prompt);
        ctx.setResultSet(null);
        return ctx;
    }

    // Helper method to generate SQL query with the help of the SqlCoder model.
    private String generateSqlWithOllama(String finalPrompt, PromptConstraints constraints) {
        String url = ollamaGenerateUrl;

        String promptToSend = finalPrompt;
        String lastModelText = "";
        String lastFailureReason = "";

        for (int attempt = 1; attempt <= Constants.OLLAMA_MAX_RETRIES; attempt++) {
            // Setting the options for the request.
            // Temperature 0 ensures deterministic output.
            // Num_predict 512 ensures the output is not too long.
            Map<String, Object> options = Map.of(
                    "temperature", 0,
                    "num_predict", 512
            );

            OllamaGenerateRequest request = new OllamaGenerateRequest(ollamaModel, promptToSend, false, options);

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<OllamaGenerateRequest> entity = new HttpEntity<>(request, headers);

                log.info("Calling Ollama (model={}). Attempt={}. Prompt chars={}", ollamaModel, attempt, promptToSend == null ? 0 : promptToSend.length());

                String resultJson = restTemplate.postForObject(url, entity, String.class);
                if (resultJson == null || resultJson.isBlank()) {
                    throw new RuntimeException("Empty response from Ollama");
                }

                JsonNode root = objectMapper.readTree(resultJson);
                String modelText = root.path("response").asText("").trim();
                lastModelText = modelText;

                String sql = null;
                try {
                    sql = extractSql(modelText);
                    lastFailureReason = "";
                } catch (RuntimeException ex) {
                    lastFailureReason = ex.getMessage();
                }

                // Canonicalize common near-miss SQL into our required safe shape before validation.
                // Keeps gates strict while tolerating minor alias/backtick mistakes by the model.
                if (sql != null) {
                    sql = canonicalizeSql(sql);
                }

                // Checking if the generated SQL contains any banned tokens.
                // SQLCoder is PostgreSQL-only, but we are using MySQL, so we need to ensure that the model does not use any PostgreSQL-only syntax.
                boolean banned = (sql == null) || SqlValidation.containsBannedTokens(sql);

                // Checking if the generated SQL contains the required user_id.
                Long effectiveUserId = constraints.userId();
                boolean missingUserFilter = effectiveUserId != null && effectiveUserId > 0 && ((sql == null) || !SqlValidation.containsRequiredUserId(sql, effectiveUserId));
                
                // Checking if the generated SQL uses the transaction table alias.
                // Ex: FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id
                boolean missingTableAlias = (sql == null) || !SqlValidation.usesTransactionAlias(sql);

                // Checks if the generated SQL contains the category filter.
                String requiredCategory = constraints.requiredCategory();
                List<String> requiredCategories = constraints.requiredCategories();
                boolean multiCategory = constraints.multiCategory();
                boolean missingCategoryFilter;
                if (requiredCategories != null && !requiredCategories.isEmpty()) {
                    if (multiCategory) {
                        missingCategoryFilter = (sql == null) || !SqlValidation.containsRequiredCategories(sql, requiredCategories);
                    } else {
                        missingCategoryFilter = (requiredCategory != null) && ((sql == null) || !SqlValidation.containsRequiredCategory(sql, requiredCategory));
                    }
                } else {
                    missingCategoryFilter = false;
                }

                // Checks if the generated SQL contains the month filter.
                // Sometimes the user initial prompt may contain a single month or multiple months.
                // We need to check if the generated SQL has captured the required months accordingly.
                Integer requiredMonth = constraints.requiredMonth();
                List<Integer> requiredMonths = constraints.monthsFound();
                boolean missingMonthFilter;
                if (constraints.multiMonth() || finalPrompt.contains("Multi-month comparison requested.")) {
                    missingMonthFilter = ((sql == null) || !SqlValidation.containsMultiMonth(sql, requiredMonths));
                } else {
                    missingMonthFilter = (requiredMonth != null) && ((sql == null) || !SqlValidation.containsSingleMonth(sql, requiredMonth));
                }

                // Checks if the generated SQL contains the year filter.
                Integer requiredYear = constraints.requiredYear();
                boolean missingYearFilter = (requiredYear != null) && ((sql == null) || !SqlValidation.containsRequiredYear(sql, requiredYear));

                /*
                    We reject the generated SQL if, they do not contain the following hard gates:
                        1. It contains any banned tokens.
                        2. If it does not contain the appropriate table alias.
                        3. If it does not contains the required user_id filter.
                        4. If it does not contains the month filter.
                        5. If it does not contains the year filter.
                        If all the above conditions are met, we accept the generated SQL otherwise we retry the model.
                */
                boolean mandatoryGates = !banned
                        && !missingTableAlias
                        && !missingUserFilter
                        && !missingMonthFilter
                        && !missingYearFilter;

                // Category is a soft requirement. But if the user has mentioned in their prompt, it must be present in the generated SQL.
                // Ex: How much did I spend on Utilities in the month of May and June ?
                // In this case, the category is a hard requirement.
                // If the user has not mentioned the category in their prompt, it is a soft requirement.
                // Ex: How much did I spend on all categories in the month of May ?
                // In this case, the category is a soft requirement.
                boolean categoryGate = (requiredCategories == null || requiredCategories.isEmpty()) || !missingCategoryFilter;

                log.info("categoryGate: {}", categoryGate);
                log.info("missingCategoryFilter: {}", missingCategoryFilter);
                log.info("mandatoryGates: {}", mandatoryGates);
                log.info("missingTableAlias: {}", missingTableAlias);
                log.info("missingUserFilter: {}", missingUserFilter);
                log.info("missingMonthFilter: {}", missingMonthFilter);
                log.info("missingYearFilter: {}", missingYearFilter);
                log.info("multiCategory: {}", multiCategory);

                if (mandatoryGates && categoryGate) {
                    log.info("Returning SQL: {}", sql);
                    log.info("mandatoryGates: {}", mandatoryGates);
                    log.info("categoryGate: {}", categoryGate);
                    return sql;
                }

                // If the generated SQL does not meet the requirements, then we retry the model with more context.
                promptToSend = finalPrompt + buildRetryHint(
                        lastFailureReason,
                        banned,
                        missingTableAlias,
                        missingUserFilter,
                        effectiveUserId,
                        missingCategoryFilter,
                        requiredCategory,
                        requiredCategories,
                        multiCategory,
                        lastModelText,
                        missingMonthFilter,
                        requiredMonth,
                        missingYearFilter,
                        requiredYear,
                        constraints.multiMonth() ? requiredMonths : List.of()
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

    // Helper method to build more context for the model to generate the correct SQL query after a failed attempt.
    private String buildRetryHint(
            String lastFailureReason,
            boolean banned,
            boolean missingTableAlias,
            boolean missingUserFilter,
            Long requiredUserId,
            boolean missingCategoryFilter,
            String requiredCategory,
            List<String> requiredCategories,
            boolean multiCategory,
            String lastModelText,
            boolean missingMonthFilter,
            Integer requiredMonth,
            boolean missingYearFilter,
            Integer requiredYear,
            List<Integer> requiredMonths
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

        if (missingTableAlias) {
            retryHint.append("- You must reference the `transaction` table as `transaction` t and use t.<col> everywhere. Do not write bare transaction.<col>.\n");
            retryHint.append("  Use this template: FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id WHERE b.user_id = <user_id>\n");
        }
        
        if (missingUserFilter && requiredUserId != null) {
            retryHint.append("- It did NOT include the required filter: user_id = ")
                    .append(requiredUserId)
                    .append(" (transaction has NO user_id column; you MUST JOIN budget and filter budget.user_id)\n");
            retryHint.append("  Use: FROM `transaction` t JOIN budget b ON b.budget_id = t.budget_id WHERE b.user_id = ")
                    .append(requiredUserId)
                    .append("\n");
            if (requiredMonths != null && requiredMonths.size() >= 2) {
                String monthsCsv = requiredMonths.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                retryHint.append("  Also include: MONTH(t.time_stamp) IN (").append(monthsCsv).append(")");
                if (requiredYear != null) {
                    retryHint.append(" AND YEAR(t.time_stamp) = ").append(requiredYear);
                }
                retryHint.append("\n");
            }
        }
        if (missingCategoryFilter) {
            if (multiCategory && requiredCategories != null && !requiredCategories.isEmpty()) {
                String csv = requiredCategories.stream()
                        .filter(Objects::nonNull)
                        .map(c -> "'" + c.replace("'", "''") + "'")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                retryHint.append("- It did NOT include the required category filter: t.category IN (")
                        .append(csv)
                        .append(")\n");
            } else if (requiredCategory != null) {
                retryHint.append("- It did NOT include the required filter: t.category = '")
                        .append(requiredCategory)
                        .append("'\n");
                if (requiredCategory.contains(" ") || requiredCategory.contains("&") || requiredCategory.contains("/")) {
                    retryHint.append("  IMPORTANT: Use the exact full category string in single quotes; do NOT split it into multiple values.\n");
                }
            }
        }
        if (missingMonthFilter) {
            if (requiredMonths != null && requiredMonths.size() >= 2) {
                String monthsCsv = requiredMonths.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                retryHint.append("- It did NOT include the required month filter: MONTH(t.time_stamp) IN (")
                        .append(monthsCsv)
                        .append(")\n");
                retryHint.append("  Do NOT use date ranges for months; use MONTH(t.time_stamp) IN (...).\n");
            } else if (requiredMonth != null) {
                retryHint.append("- It did NOT include the required month filter: MONTH(t.time_stamp) = ")
                        .append(requiredMonth)
                        .append("\n");
                retryHint.append("  Do NOT use a date range for a single month; use MONTH(t.time_stamp) = ")
                        .append(requiredMonth)
                        .append(".\n");
            }
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

    // We expect sqlQuery generated by the model to be scoped to a specific user
    // hence we need to fetch the userId from the budgetId.
    private Long inferUserIdFromBudget(Long budgetId) {
        if (budgetId == null) return null;
        return budgetRepository.findById(budgetId)
                .map(Budget::getUserId)
                .orElse(null);
    }

    // In some cases, the user prompt may not contain a specific category.
    // Ex: How much did I spend on all categories in the month of May ?
    // Ex: How much did I overspend in the month of May ?
    // In such cases, we need to return a category breakdown, 
    // otherwise the insights model cannot name the category.
    private static boolean isTopCategoryQuestion(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase(Locale.ROOT);
        return p.contains("overspend")
                || p.contains("over spend")
                || p.contains("spent the most")
                || p.contains("spend the most")
                || p.contains("most spent")
                || p.contains("highest spend")
                || p.contains("highest spending")
                || p.contains("where did i spend the most")
                || p.contains("which category") && (p.contains("most") || p.contains("highest"));
    }

    private String extractSql(String modelText) {
        if (modelText == null) {
            throw new RuntimeException("Model returned null text");
        }
        String trimmed = modelText.trim();

        // At all times, the generated SQL query must be SELECT query.
        // We do not allow any other type of query.
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

    /**
     * Make the model SQL conform to the app's required "safe" shape:
     * - FROM `transaction` t (backticked + alias t)
     * - Use t.<col> for transaction columns
     * - Replace `transaction`.<col> / transaction.<col> with t.<col>
     *
     * This is intentionally conservative and only rewrites known columns to avoid touching budget columns.
     */
    private String canonicalizeSql(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        String out = sql;

        // Ensure `transaction` is backticked and aliased as t.
        // Case 1: FROM transaction (no alias) — common model output:
        //   FROM transaction JOIN budget ...
        //   FROM transaction WHERE ...
        // We rewrite it whenever the next token is JOIN/WHERE/GROUP/ORDER/LIMIT or end-of-string.
        out = out.replaceAll(
                "(?is)\\bfrom\\s+transaction\\b(\\s*)(?=(?:join|where|group\\s+by|order\\s+by|limit)\\b|$)",
                "FROM `transaction` t$1"
        );
        out = out.replaceAll(
                "(?is)\\bfrom\\s+`transaction`\\b(\\s*)(?=(?:join|where|group\\s+by|order\\s+by|limit)\\b|$)",
                "FROM `transaction` t$1"
        );

        // Case 2: FROM transaction <alias> (alias not guaranteed to be t)
        Matcher fromAlias = java.util.regex.Pattern.compile("(?is)\\bfrom\\s+`?transaction`?\\s+([a-zA-Z]\\w*)").matcher(out);
        if (fromAlias.find()) {
            String alias = fromAlias.group(1);
            if (!"t".equalsIgnoreCase(alias)) {
                out = out.replaceAll("(?is)\\bfrom\\s+`?transaction`?\\s+" + java.util.regex.Pattern.quote(alias) + "\\b", "FROM `transaction` t");
                // Replace <alias>.col -> t.col
                out = out.replaceAll("(?is)\\b" + java.util.regex.Pattern.quote(alias) + "\\s*\\.", "t.");
            } else {
                // Ensure backticks in FROM when alias already t
                out = out.replaceAll("(?is)\\bfrom\\s+transaction\\s+t\\b", "FROM `transaction` t");
            }
        }

        // Replace any remaining transaction.<col> / `transaction`.<col> with t.<col>
        out = out.replaceAll("(?is)\\b`?transaction`?\\s*\\.", "t.");

        // Prefix known transaction columns when unqualified.
        out = out.replaceAll("(?i)(?<!\\.)\\bcategory\\b", "t.category");
        out = out.replaceAll("(?i)(?<!\\.)\\bamount_spend\\b", "t.amount_spend");
        out = out.replaceAll("(?i)(?<!\\.)\\btime_stamp\\b", "t.time_stamp");

        return out;
    }

    // In order to ensure that we do not allow the model to return a non-SELECT query,
    // we need to check if the generated text looks like a SELECT query.
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

    // === Prompt parsing helpers ===
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

    private List<Integer> attemptMonthsFromPrompt(String prompt) {
        if (prompt == null) return List.of();
        Matcher matcher = java.util.regex.Pattern.compile("(?m)^- month must be (\\d+)\\b").matcher(prompt);
        List<Integer> months = new ArrayList<>();
        while (matcher.find()) {
            months.add(Integer.parseInt(matcher.group(1)));
        }
        // Also handle the IN list hint used for multi-month prompts (with optional alias).
        Matcher inMatcher = java.util.regex.Pattern.compile(
                "(?mi)MONTH\\(\\s*(?:`?transaction`?|`?t`?|t)\\s*\\.\\s*`?time_stamp`?\\s*\\)\\s*IN\\s*\\(([^)]*)\\)"
        ).matcher(prompt);
        if (inMatcher.find()) {
            String[] parts = inMatcher.group(1).split(",");
            for (String p : parts) {
                try {
                    months.add(Integer.parseInt(p.trim()));
                } catch (NumberFormatException ignored) {
                    // skip invalid entries
                }
            }
        }
        return months.stream().distinct().toList();
    }

    // Helper method to detect the category from the user prompt.
    private String detectCategory(String userPrompt) {
        List<String> cats = detectCategories(userPrompt);
        return cats.isEmpty() ? null : cats.get(0);
    }

    private List<String> detectCategories(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) return List.of();
        String normalizedPrompt = userPrompt.toLowerCase(Locale.ROOT);

        // - Scan for multi-word categories first (e.g., "rent or mortgage") to avoid partial/ambiguous matches
        // - Then scan for single-word categories (e.g., "insurance")
        // - Sort matches by where they appear in the prompt (left-to-right)
        // - De-duplicate while preserving order
        List<CategoryMatch> matches = new ArrayList<>();
        if (cachedMultiWordCategories != null) {
            for (String category : cachedMultiWordCategories) {
                String normalizedCategory = category.toLowerCase(Locale.ROOT);
                int firstIndex = normalizedPrompt.indexOf(normalizedCategory);
                if (firstIndex >= 0) matches.add(new CategoryMatch(firstIndex, category));
            }
        }
        if (cachedSingleWordCategories != null) {
            for (String category : cachedSingleWordCategories) {
                String normalizedCategory = category.toLowerCase(Locale.ROOT);
                int firstIndex = normalizedPrompt.indexOf(normalizedCategory);
                if (firstIndex >= 0) matches.add(new CategoryMatch(firstIndex, category));
            }
        }

        matches.sort(Comparator.comparingInt(CategoryMatch::firstIndex));

        LinkedHashSet<String> categoriesInPromptOrder = new LinkedHashSet<>();
        for (CategoryMatch match : matches) {
            categoriesInPromptOrder.add(match.category);
        }
        return new ArrayList<>(categoriesInPromptOrder);
    }

    private record CategoryMatch(int firstIndex, String category) {}

    // Helper method to detect the month from the user prompt.
    private Integer detectMonth(String userPrompt) {
        List<Integer> months = detectMonths(userPrompt);
        return months.size() == 1 ? months.get(0) : null;
    }

    // Helper method to detect the months from the user prompt.
    private List<Integer> detectMonths(String userPrompt) {
        // Gather all the months mentioned in the user prompt.
        List<int[]> monthsListedInPrompt = new ArrayList<>();
        for (Map.Entry<String, Integer> monthName : Constants.MONTH_NAME_TO_NUMBER.entrySet()) {
            int indexOfMonthNameInPrompt = userPrompt.toLowerCase(Locale.ROOT).indexOf(monthName.getKey());
            if (indexOfMonthNameInPrompt >= 0) {
                monthsListedInPrompt.add(new int[]{indexOfMonthNameInPrompt, monthName.getValue()});
            }
        }

        // Sort by the order of appearance of the months in the user prompt and also remove any duplicate months mentioned in the prompt.
        // Ex: How much did I spend in March, March and April ?
        // It removes the additional March mentioned in the prompt.
        monthsListedInPrompt.sort(Comparator.comparingInt(month -> month[0]));

        Set<Integer> orderedMonths = new LinkedHashSet<>();
        for (int[] month : monthsListedInPrompt) {
            orderedMonths.add(month[1]);
        }

        return new ArrayList<>(orderedMonths);
    }

    // Helper method to detect the year from the user prompt.
    private Integer detectYear(String userPrompt) {
        Matcher promptYearPatternMatcher = Constants.PROMPT_YEAR_PATTERN.matcher(userPrompt);
        if (promptYearPatternMatcher.find()) {
            return Integer.parseInt(promptYearPatternMatcher.group(1));
        }
        return null;
    }

    // Helper method to infer the year from the budgetId if the year is not found in the user prompt.
    // If the user prompt does not contain the year, we infer the year from the budgetId.
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

    // ----- Resource loading/caching  helper methods ------
    // Helper method to cache the sql coder rules, schema and transaction categories.
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

            // Split the categories into two buckets:
            // - multi-word categories: contain whitespace (e.g., "rent or mortgage")
            // - single-word categories: no whitespace (e.g., "insurance")
            //
            // We keep multi-word categories sorted by length DESC so the longest phrases are matched first.
            cachedMultiWordCategories = cachedTransactionCategories.stream()
                    .filter(TextToSqlGenerationService::isMultiWordCategory)
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();

            cachedSingleWordCategories = cachedTransactionCategories.stream()
                    .filter(category -> !isMultiWordCategory(category))
                    .toList();
        }
    }

    private static boolean isMultiWordCategory(String category) {
        if (category == null) return false;
        // Any whitespace makes it multi-word; also treat connectors as multi-word categories.
        return category.chars().anyMatch(Character::isWhitespace);
    }

    // Helper method to read the contents of the resource files.
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

    // This file contains the rules and suggestions that will be used by the sql coder to ensure
    // that we stick to the rules and suggestions while generating the sql queries.
    private Resource loadSQLRules() {
        Resource resource = resourceLoader.getResource("classpath:ai/sqlcoder-rules.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/sqlcoder-rules.txt");
        }
        return resource;
    }

    // This file contains the schema of the database that the model has access to.
    // Helps the sqlcoder to understand the schema and to generate the sql queries accordingly.
    private Resource loadSQLSchema() {
        Resource resource = resourceLoader.getResource("classpath:ai/sqlcoder-schema.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/sqlcoder-schema.txt");
        }
        return resource;
    }

    // This file contains the list of transaction categories used in the application.
    // This helps the sqlcoder to scope the categories and to generate the sql queries for the specific categories.
    private Resource loadTransactionCategories() {
        Resource resource = resourceLoader.getResource("classpath:ai/transaction-categories.txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing resource: classpath:ai/transaction-categories.txt");
        }
        return resource;
    }
}

