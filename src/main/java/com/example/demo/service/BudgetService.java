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
       Budget newBudgetInfo = new Budget(null, budgetSetupInput.getId(),
                                    budgetSetupInput.getStartDate(), budgetSetupInput.getEndDate(),
                                    budgetSetupInput.getBudgetAllocated(), budgetSetupInput.getBudgetRemaining(), true);
       return budgetRepository.save(newBudgetInfo);
    }

    public Optional<Budget> fetchBudgetDetails(Long id)
    {
        return budgetRepository.findByIdAndIsActiveTrue(id);
    }

    public Budget deactivateCurrentBudgetAndCreateNewBudget(Long budgetId, BudgetSetupInput budgetSetUpInput) {
        Optional<Budget> currentBudget = budgetRepository.findById(budgetId);
        if (currentBudget.isPresent()) {
            Budget budget = currentBudget.get();
            budget.setIsActive(false);
            budgetRepository.save(budget);
        }

        return budgetSetup(budgetSetUpInput);
    }

}
