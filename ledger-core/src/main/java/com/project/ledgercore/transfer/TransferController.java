package com.project.ledgercore.transfer;

import com.project.ledgercore.common.Money;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        Money amount = request.toMoney();
        UUID transactionId = transfers.transfer(request.fromAccountId(), request.toAccountId(), amount);
        return new TransferResponse(transactionId, request.fromAccountId(), request.toAccountId(),
                amount.minorUnits(), amount.currency().getCurrencyCode());
    }
}