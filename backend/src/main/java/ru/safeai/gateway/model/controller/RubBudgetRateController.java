package ru.safeai.gateway.model.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.model.service.RubBudgetRateService;

@RestController
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class RubBudgetRateController {
    private final RubBudgetRateService rates;

    public RubBudgetRateController(RubBudgetRateService rates) { this.rates = rates; }

    @GetMapping("/api/admin/models/policies/rub-quote")
    public ResponseEntity<RubBudgetRateService.Quote> quote() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rates.current());
    }
}
