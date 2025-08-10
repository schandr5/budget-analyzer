package com.example.demo.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Date;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long budget_id;

    @JoinColumn(name = "id", nullable = false)
    private Long id;

    private LocalDate start_date;

    private LocalDate end_date;

    private Long budget_allocated;

    private Long budget_remaining;
}
