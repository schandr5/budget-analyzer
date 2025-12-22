package com.example.demo.service;

import com.example.demo.dto.BudgetSetupInput;
import com.example.demo.model.Budget;
import com.example.demo.repository.BudgetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class BudgetService {

    @Autowired
    BudgetRepository budgetRepository;

    public Budget budgetSetup(BudgetSetupInput budgetSetupInput)
    {
       Budget newBudgetInfo = new Budget(null, budgetSetupInput.getUser_id(),
                                    budgetSetupInput.getStartDate(), budgetSetupInput.getEndDate(),
                                    budgetSetupInput.getBudgetAllocated(), budgetSetupInput.getBudgetRemaining(), true);
       return budgetRepository.save(newBudgetInfo);
    }

    public Optional<Budget> fetchActiveBudgetDetailsForUser(Long userId)
    {
        return budgetRepository.findByUserIdAndIsActiveTrue(userId);
    }

    public Optional<Budget> fetchBudgetDetailsForUserUsingBudgetId(Long budgetId)
    {
        return budgetRepository.findByBudgetIdAndIsActiveTrue(budgetId);
    }

    public Budget deactivateCurrentBudgetAndCreateNewBudget(Budget currentBudget, BudgetSetupInput budgetSetUpInput) {
        // Deactivate the current budget
        currentBudget.setIsActive(false);
        budgetRepository.save(currentBudget);

        // Create a new budget
        return budgetSetup(budgetSetUpInput);
    }

    public Budget modifyBudgetForExistingCycle(Long currentBudgetId, Long additionalBudgetAllocated) {

        if(additionalBudgetAllocated <= 0) {
            throw new RuntimeException("Additional budget allocated must be positive");
        }

        Budget budget = fetchBudgetDetailsForUserUsingBudgetId(currentBudgetId).orElseThrow(() -> new RuntimeException("Budget not found for the user"));
        
        Long newBudgetAllocated = budget.getBudgetAllocated() + additionalBudgetAllocated;
        budget.setBudgetAllocated(newBudgetAllocated);

        Long newBudgetRemaining = additionalBudgetAllocated + budget.getBudgetRemaining();
        budget.setBudgetRemaining(newBudgetRemaining);

        return budgetRepository.save(budget);
    }



}
