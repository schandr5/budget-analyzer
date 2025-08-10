package com.example.demo.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetDetails {
    private Long budget_id;

    private Long id;

    private LocalDate start_date;

    private LocalDate end_date;

    private Long budget_allocated;

    private Long budget_remaining;
}
