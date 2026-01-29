package com.example.demo.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class GeneratedSqlContext {

    private String requiredCategory;

    private List<String> requiredCategories;

    private Integer requiredMonth;

    private Integer requiredYear;

    private String originalPrompt;

    private List<Map<String, Object>> resultSet;

    private String sqlQuery;
}
