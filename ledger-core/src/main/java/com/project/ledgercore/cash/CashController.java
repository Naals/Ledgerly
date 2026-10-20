package com.project.ledgercore.cash;

import com.project.ledgercore.common.Money;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class CashController {

    private final CashService cash;

    public CashController(CashService cash) {
        this.cash = cash;
    }

    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public CashResponse deposit(@PathVariable UUID accountId, @Valid @RequestBody CashRequest request) {
        Money amount = request.toMoney();
        UUID transactionId = cash.deposit(accountId, amount);
        return new CashResponse(
                transactionId, accountId, "DEPOSIT", amount.minorUnits(), amount.currency().getCurrencyCode());
    }

    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public CashResponse withdraw(@PathVariable UUID accountId, @Valid @RequestBody CashRequest request) {
        Money amount = request.toMoney();
        UUID transactionId = cash.withdraw(accountId, amount);
        return new CashResponse(
                transactionId, accountId, "WITHDRAWAL", amount.minorUnits(), amount.currency().getCurrencyCode());
    }
}
