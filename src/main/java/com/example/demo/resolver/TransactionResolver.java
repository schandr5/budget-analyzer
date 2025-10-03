package com.example.demo.resolver;
import org.springframework.stereotype.Controller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import com.example.demo.dto.TransactionInput;
import com.example.demo.dto.TransactionOutput;
import com.example.demo.model.Transaction;
import com.example.demo.service.TransactionService;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@Slf4j
public class TransactionResolver {

    @Autowired
    TransactionService transactionService;

    @MutationMapping
    public TransactionOutput addTransaction(@Argument("transactionInput") TransactionInput transactionInput) {
        log.info("Adding transaction for budget: {}", transactionInput.getBudgetId());
        Transaction transaction = transactionService.saveTransaction(transactionInput);

        if (transaction != null) {
            return new TransactionOutput(transaction.getTransactionId(), transaction.getBudgetId(), transaction.getTransactionAmount(), transaction.getTransactionDate(), transaction.getTransactionCategory(), transaction.getTransactionPriority());
        }
        else {
            throw new RuntimeException("Unable to add transaction");
        }
    }

    @QueryMapping
    public List<TransactionOutput> fetchTransactions(@Argument("budgetId") Long budgetId) {
        log.info("Fetching transactions for budget: {}", budgetId);
        List<Transaction> transactions = transactionService.retrieveTransaction(budgetId);

        return transactions.stream()
                .map(t -> new TransactionOutput(t.getTransactionId(), t.getBudgetId(), t.getTransactionAmount(), 
                        t.getTransactionDate(), t.getTransactionCategory(), t.getTransactionPriority()))
                .collect(Collectors.toList());
    }

}
