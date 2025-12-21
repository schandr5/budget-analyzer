package com.example.demo.service;

import com.example.demo.constants.Constants;
import com.example.demo.dto.TransactionInput;
import com.example.demo.enums.TransactionPriority;
import com.example.demo.model.Budget;
import com.example.demo.model.Transaction;
import com.example.demo.repository.TransactionRepository;
import com.example.demo.repository.BudgetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionService {

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    BudgetService budgetService;

    @Autowired
    BudgetRepository budgetRepository;

    public record TransactionResult(Transaction transaction, Long budgetRemaining) {}

    @Transactional
    public TransactionResult saveTransaction(TransactionInput transactionInput) {

        Budget budget = budgetService.fetchBudgetDetailsForUserUsingBudgetId(transactionInput.getBudgetId())
                .orElseThrow(() -> new RuntimeException("Budget not found for the user"));

        if (!isTransactionValid(budget, transactionInput.getTransactionAmount())) {
            throw new RuntimeException("Transaction amount is not valid for the budget");
        }

        double transactionPercentage = (double) transactionInput.getTransactionAmount() / transactionInput.getBudgetAllocated();
        TransactionPriority priority = determinePriority(transactionPercentage);

        Transaction transaction = new Transaction(
                null,
                transactionInput.getBudgetId(),
                transactionInput.getTransactionAmount(),
                transactionInput.getTransactionDate(),
                transactionInput.getTransactionCategory(),
                priority);

        Transaction savedTransaction = transactionRepository.save(transaction);

        updateBudgetRemaining(budget, transaction.getTransactionAmount());

        return new TransactionResult(savedTransaction, budget.getBudgetRemaining());
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
        return (transactionAmount > 0) &&
                (transactionAmount <= budget.getBudgetRemaining());
    }

    private void updateBudgetRemaining(Budget budget, Long transactionAmount) {
        budget.setBudgetRemaining(budget.getBudgetRemaining() - transactionAmount);
        budgetRepository.save(budget);
    }

}
