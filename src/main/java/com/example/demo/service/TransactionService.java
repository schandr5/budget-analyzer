package com.example.demo.service;

import com.example.demo.dto.TransactionInput;
import com.example.demo.enums.TransactionPriority;
import com.example.demo.model.Transaction;
import com.example.demo.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {

    @Autowired
    TransactionRepository transactionRepository;

    public Transaction saveTransaction(TransactionInput transactionInput) {
        TransactionPriority priority = TransactionPriority.LOW;
        double transactionPercentage = (double) transactionInput.getTransactionAmount() / transactionInput.getBudgetAllocated();
        
        if (transactionPercentage > 0.3) {
            priority = TransactionPriority.HIGH;
        } else if (transactionPercentage < 0.3 && transactionPercentage > 0.1) {
            priority = TransactionPriority.MEDIUM;
        } else {
            priority = TransactionPriority.LOW;
        }

        Transaction transaction = new Transaction(null, transactionInput.getBudgetId(),transactionInput.getTransactionAmount(), transactionInput.getTransactionDate(),
                                                                    transactionInput.getTransactionCategory(), priority);

        return transactionRepository.save(transaction);
    }

    public List<Transaction> retrieveTransaction(Long budgetId) {
        return transactionRepository.findByBudgetId(budgetId);
    }
}
