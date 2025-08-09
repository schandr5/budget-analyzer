package com.example.demo.service;

import com.example.demo.dto.BudgetSetupInput;
import com.example.demo.model.Budget;
import com.example.demo.repository.BudgetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BudgetSetupService {

    @Autowired
    BudgetRepository budgetRepository;

    public Budget budgetSetup(BudgetSetupInput budgetSetupInput)
    {
       Budget newBudgetInfo = new Budget(null, budgetSetupInput.getId(),
                                    budgetSetupInput.getStart_date(), budgetSetupInput.getEnd_date(),
                                    budgetSetupInput.getBudget_allocated(), budgetSetupInput.getBudget_remaining());
       return budgetRepository.save(newBudgetInfo);
    }

}
