package com.example.demo.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetSetupInput {
    private Long budget_id;

    private Long id;

    private Date start_date;

    private Date end_date;

    private Long budget_allocated;

    private Long budget_remaining;
}
