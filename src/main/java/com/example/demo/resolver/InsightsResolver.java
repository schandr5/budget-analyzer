package com.example.demo.resolver;

import com.example.demo.dto.GeneratedSqlContext;
import com.example.demo.service.InsightsGenerationService;
import com.example.demo.service.SqlQueryExecutionService;
import com.example.demo.service.TextToSqlGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

@Controller
@Slf4j
public class InsightsResolver {

    @Autowired
    TextToSqlGenerationService textToSqlGenerationService;

    @Autowired
    SqlQueryExecutionService sqlQueryExecutionService;

    @Autowired
    InsightsGenerationService insightsGenerationService;

    @QueryMapping
    public String fetchInsights(@Argument("budgetId") Long budgetId, @Argument("prompt") String prompt) {
        log.info("Fetching insights for the budget: {}", budgetId);

        // Generate SQL query with the help of SqlCoder gen ai model.
        GeneratedSqlContext generatedSqlContext = textToSqlGenerationService.generateSql(budgetId, prompt);

        // Execute the query and fetch the ResultSet.
        List<Map<String, Object>> results = sqlQueryExecutionService.executeQuery(generatedSqlContext.getSqlQuery());
        log.info("SQL query results: {}", results);
        generatedSqlContext.setResultSet(results);
        
        // Generate insights with the help of DeepSeek gen ai model and return the insights to the user.
        String insights = insightsGenerationService.generateInsights(generatedSqlContext);
        log.info("Insights: {}", insights);
        return insights;
    }
}