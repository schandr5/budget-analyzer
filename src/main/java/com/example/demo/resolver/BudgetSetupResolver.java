package com.example.demo.resolver;

import com.example.demo.dto.BudgetDetails;
import com.example.demo.dto.BudgetSetupInput;
import com.example.demo.model.Budget;
import com.example.demo.service.BudgetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Optional;

@Controller
@Slf4j
public class BudgetSetupResolver {

    @Autowired
    BudgetService budgetService;

    @MutationMapping
    public BudgetDetails setupBudgetForNewUser(@Argument("budgetSetupInput") BudgetSetupInput budgetSetupInput)
    {
        log.info("Setting up budget for new user: {} ", budgetSetupInput.getId());

        Budget budgetInfo = budgetService.budgetSetup(budgetSetupInput);

        if (budgetInfo != null)
        {
            return new BudgetDetails(budgetInfo.getBudgetId(), budgetInfo.getId(), budgetInfo.getStartDate(),
                                budgetInfo.getEndDate(), budgetInfo.getBudgetAllocated(), budgetInfo.getBudgetRemaining(), budgetInfo.getIsActive());
        }
        else {
            throw new RuntimeException("Unable to save budget details for new user");
        }
    }

    @QueryMapping
    public BudgetDetails fetchBudgetDetailsForExistingUser(@Argument("id") Long id) {
        log.info("Fetch budget details for the existing user: {}", id);

        Optional<Budget> budgetInfo = budgetService.fetchBudgetDetails(id);

        if (budgetInfo.isPresent())
        {
            return new BudgetDetails(budgetInfo.get().getBudgetId(), budgetInfo.get().getId(), budgetInfo.get().getStartDate(),
                    budgetInfo.get().getEndDate(), budgetInfo.get().getBudgetAllocated(), budgetInfo.get().getBudgetRemaining(), budgetInfo.get().getIsActive());
        }
        else {
            throw new RuntimeException("Unable to fetch budget details for existing user");
        }
    }
}
