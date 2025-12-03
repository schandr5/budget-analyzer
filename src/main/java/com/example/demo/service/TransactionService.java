package com.example.demo.service;

import com.example.demo.constants.Constants;
import com.example.demo.dto.TransactionInput;
import com.example.demo.enums.TransactionPriority;
import com.example.demo.model.Budget;
import com.example.demo.model.Transaction;
import com.example.demo.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    BudgetService budgetService;

    public Transaction saveTransaction(TransactionInput transactionInput) {

        Budget budget = budgetService.fetchBudgetDetailsForUserUsingBudgetId(transactionInput.getBudgetId())
                .orElseThrow(() -> new RuntimeException("Budget not found for the user"));

        if (!isTransactionValid(budget, transactionInput.getTransactionAmount())) {
            throw new RuntimeException("Transaction amount is not valid for the budget");
        }

        TransactionPriority priority = TransactionPriority.LOW;
        double transactionPercentage = (double) transactionInput.getTransactionAmount() / transactionInput.getBudgetAllocated();

        priority = determinePriority(transactionPercentage);

        Transaction transaction = new Transaction(null, transactionInput.getBudgetId(),transactionInput.getTransactionAmount(), transactionInput.getTransactionDate(),
                                                                    transactionInput.getTransactionCategory(), priority);

        return transactionRepository.save(transaction);
    }

    public List<Transaction> retrieveTransaction(Long budgetId) {

        if (!budgetService.fetchBudgetDetailsForUserUsingBudgetId(budgetId).isPresent()) {
            throw new RuntimeException("Budget not found for the user");
        }

        return transactionRepository.findByBudgetId(budgetId);
    }

    private TransactionPriority determinePriority(double transactionPercentage) {

        if (transactionPercentage > Constants.UPPER_BOUND) {
            return TransactionPriority.HIGH;
        } else if (transactionPercentage < Constants.UPPER_BOUND && transactionPercentage > Constants.LOWER_BOUND) {
            return TransactionPriority.MEDIUM;
        } 
    
        return TransactionPriority.LOW;
    }


    private boolean isTransactionValid(Budget budget, Long transactionAmount) { 
        Long budgetRemaining = budget.getBudgetRemaining();
        Long budgetAllocated = budget.getBudgetAllocated();

        return (transactionAmount > 0) &&
                (transactionAmount <= budgetRemaining);
    }

}
