package com.example.demo.model;


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
    private long transactionId;

    @JoinColumn(name = "id", nullable = false)
    private long id;

    @Column(name = "amount_spend")
    private long transactionAmount;

    @Column(name = "time_stamp")
    private LocalDate transactionDate;

    @Column(name = "category")
    private String transactionCategory;
}
