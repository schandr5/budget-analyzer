package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;


@Service
@Slf4j
public class SqlQueryExecutionService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> executeQuery(String query) {
        try {
            return jdbcTemplate.queryForList(query);
        } catch (Exception e) {
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
            if (root == null) {
                root = e;
            }
            String rootMsg = (root.getMessage() == null) ? "<no message>" : root.getMessage();
            log.error("Failed to execute query. Root cause: {}. Query: {}", rootMsg, query, e);
            throw new RuntimeException("Failed to execute query: " + query + " | DB error: " + rootMsg, e);
        }
    }
}