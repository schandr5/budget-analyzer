package com.example.demo.resolver;

import com.example.demo.dto.BudgetDetails;
import com.example.demo.dto.BudgetSetupInput;
import com.example.demo.model.Budget;
import com.example.demo.service.BudgetSetupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

import java.util.Optional;

@Controller
@Slf4j
public class BudgetSetupResolver {

    @Autowired
    BudgetSetupService budgetSetupService;

    @MutationMapping
    public BudgetDetails setupBudgetForNewUser(BudgetSetupInput budgetSetupInput)
    {
        log.info("Setting up budget for new user: {} ", budgetSetupInput.getBudget_id());
        Budget budgetInfo = budgetSetupService.budgetSetup(budgetSetupInput);

        if (budgetInfo != null)
        {
            return new BudgetDetails(budgetInfo.getBudget_id(), budgetInfo.getId(), budgetInfo.getStart_date(),
                                budgetInfo.getEnd_date(), budgetInfo.getBudget_allocated(), budgetInfo.getBudget_remaining());
        }
        else {
            throw new RuntimeException("Unable to save budget details for new user");
        }
    }
}
