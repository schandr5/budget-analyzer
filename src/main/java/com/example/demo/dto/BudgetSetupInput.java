package com.example.demo.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetSetupInput {
    private Long id;

    private LocalDate startDate;

    private LocalDate endDate;

    private Long budgetAllocated;

    private Long budgetRemaining;
}
