package com.example.demo.dto;


import com.example.demo.enums.TransactionPriority;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class TransactionOutput {
    private long transactionId;

    private long budgetId;

    private long transactionAmount;

    private LocalDate transactionDate;

    private String transactionCategory;

    private TransactionPriority transactionPriority;

    private Long budgetRemaining;

}
