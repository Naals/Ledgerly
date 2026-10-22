package com.project.ledgercore.account;

import com.project.ledgercore.common.Currencies;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }


    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.openCustomerAccount(request.ownerRef(), Currencies.parse(request.currency()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(account.id())
                .toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.get(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        return BalanceResponse.from(id, accounts.balanceOf(id));
    }

    @GetMapping("/{id}/entries")
    public EntryPageResponse entries(@PathVariable UUID id,
                                     @RequestParam(required = false) Long before,
                                     @RequestParam(defaultValue = "50") int limit) {
        return EntryPageResponse.from(accounts.entriesOf(id, before, limit));
    }

    @GetMapping
    public List<AccountResponse> list(@RequestParam String ownerRef) {
        return accounts.customerAccountOf(ownerRef)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }
}
