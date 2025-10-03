package com.example.demo.model;


import com.example.demo.enums.TransactionPriority;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @JoinColumn(name = "budget_id", nullable = false)
    private Long budgetId;

    @Column(name = "amount_spend")
    private Long transactionAmount;

    @Column(name = "time_stamp")
    private LocalDate transactionDate;

    @Column(name = "category")
    private String transactionCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority")
    private TransactionPriority transactionPriority;
}
