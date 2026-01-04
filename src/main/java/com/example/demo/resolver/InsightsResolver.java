package com.example.demo.resolver;

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

    @QueryMapping
    public String fetchInsights(@Argument("budgetId") Long budgetId, @Argument("prompt") String prompt) {
        log.info("Fetching insights for the budget: {}", budgetId);
        String sqlQuery = textToSqlGenerationService.generateSql(budgetId, prompt);
        List<Map<String, Object>> results = sqlQueryExecutionService.executeQuery(sqlQuery);
        log.info("SQL query results: {}", results);
        return sqlQuery;
    }
}