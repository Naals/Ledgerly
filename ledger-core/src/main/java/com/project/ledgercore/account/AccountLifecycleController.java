package com.project.ledgercore.account;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{id}")
public class AccountLifecycleController {

    private final AccountLifecycleService lifecycle;

    public AccountLifecycleController(AccountLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/freeze")
    public AccountResponse freeze(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return AccountResponse.from(lifecycle.freeze(id, reasonOf(request)));
    }

    @PostMapping("/unfreeze")
    public AccountResponse unfreeze(@PathVariable UUID id,
                                    @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return AccountResponse.from(lifecycle.unfreeze(id, reasonOf(request)));
    }

    @PostMapping("/close")
    public AccountResponse close(@PathVariable UUID id,
                                 @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return AccountResponse.from(lifecycle.close(id, reasonOf(request)));
    }

    @GetMapping("/status-history")
    public List<StatusEventResponse> history(@PathVariable UUID id) {
        return lifecycle.history(id).stream().map(StatusEventResponse::from).toList();
    }

    private static String reasonOf(StatusChangeRequest request) {
        return request == null ? null : request.reason();
    }
}