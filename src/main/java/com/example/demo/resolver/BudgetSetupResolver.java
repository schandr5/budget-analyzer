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
        log.info("Setting up budget for new user: {} ", budgetSetupInput.getUser_id());
    
        Optional<Budget> budgetInfo = budgetService.fetchActiveBudgetDetailsForUser(budgetSetupInput.getUser_id());

        // Check if the budget already exists for the user
        if(budgetInfo.isPresent())
        {
            throw new RuntimeException("Budget already exists for the user: " + budgetSetupInput.getUser_id());
        }

        Budget newBudgetInfo = budgetService.budgetSetup(budgetSetupInput);

        // Check if the budget is saved successfully
        if (newBudgetInfo != null)
        {
            return new BudgetDetails(newBudgetInfo.getBudgetId(), newBudgetInfo.getUserId(), newBudgetInfo.getStartDate(),
                                newBudgetInfo.getEndDate(), newBudgetInfo.getBudgetAllocated(), newBudgetInfo.getBudgetRemaining(), newBudgetInfo.getIsActive());
        }
        else {
            throw new RuntimeException("Unable to save budget details for new user");
        }
    }

    @QueryMapping
    public BudgetDetails fetchBudgetDetailsForExistingUser(@Argument("id") Long id) {
        log.info("Fetch budget details for the existing user: {}", id);

        Optional<Budget> budgetInfo = budgetService.fetchActiveBudgetDetailsForUser(id);
        
        if (budgetInfo.isPresent()) {
            return new BudgetDetails(budgetInfo.get().getBudgetId(), budgetInfo.get().getUserId(), budgetInfo.get().getStartDate(), budgetInfo.get().getEndDate(), budgetInfo.get().getBudgetAllocated(), budgetInfo.get().getBudgetRemaining(), budgetInfo.get().getIsActive());
        }

        return null;
    }

    @MutationMapping
    public BudgetDetails modifyBudgetForExistingCycle(@Argument("currentBudgetId") Long currentBudgetId, @Argument("additionalBudgetAllocated") Long additionalBudgetAllocated) {
        log.info("Modify budget for the existing cycle: {}", currentBudgetId);

        Optional<Budget> budgetInfo = budgetService.fetchBudgetDetailsForUserUsingBudgetId(currentBudgetId);
        if (!budgetInfo.isPresent()) {
            throw new RuntimeException("Budget not found for the user" + currentBudgetId);
        }

        Budget updatedBudgetInfo = budgetService.modifyBudgetForExistingCycle(currentBudgetId, additionalBudgetAllocated);

        if (updatedBudgetInfo != null) {
            return new BudgetDetails(updatedBudgetInfo.getBudgetId(), updatedBudgetInfo.getUserId(), updatedBudgetInfo.getStartDate(), updatedBudgetInfo.getEndDate(), updatedBudgetInfo.getBudgetAllocated(), updatedBudgetInfo.getBudgetRemaining(), updatedBudgetInfo.getIsActive());
        }
        else {
            throw new RuntimeException("Unable to modify budget for the existing cycle");
        }
        
    }

    @MutationMapping
    public BudgetDetails updateIsActiveForCurrentBudgetCycle(@Argument("currentBudgetId") Long currentBudgetId, @Argument("budgetSetUpInput") BudgetSetupInput budgetSetUpInput) {
        log.info("Set the isActive flag for the existing budget cycle to false");

        // Check if the current budget is an active budget for the user
        Optional<Budget> budgetInfo = budgetService.fetchBudgetDetailsForUserUsingBudgetId(currentBudgetId);
        if (!budgetInfo.isPresent()) {
            throw new RuntimeException("Budget not found for the user" + budgetSetUpInput.getUser_id());
        }

        if(!budgetInfo.get().getUserId().equals(budgetSetUpInput.getUser_id())) {
            throw new RuntimeException("Budget id mismatch for the user: " + budgetSetUpInput.getUser_id());
        }
        
        // Deactivate the current budget and create a new budget
        Budget updatedBudgetInfo = budgetService.deactivateCurrentBudgetAndCreateNewBudget(budgetInfo.get(), budgetSetUpInput);
        if (updatedBudgetInfo != null) {
                return new BudgetDetails(updatedBudgetInfo.getBudgetId(), updatedBudgetInfo.getUserId(), updatedBudgetInfo.getStartDate(),
                        updatedBudgetInfo.getEndDate(), updatedBudgetInfo.getBudgetAllocated(), updatedBudgetInfo.getBudgetRemaining(), updatedBudgetInfo.getIsActive());
        }
        else {
            throw new RuntimeException("Unable to update isActive flag for the existing budget cycle");
        }
    }
}
