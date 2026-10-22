package com.project.ledgercore.account;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class AccountSearchApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Test
    void listsTheCustomerAccountsOfAnOwner() throws Exception {
        String owner = "search-" + UUID.randomUUID();
        accounts.create(NewAccount.customer(owner, Currency.getInstance("KZT")));
        accounts.create(NewAccount.customer(owner, Currency.getInstance("USD")));
        accounts.create(NewAccount.customer("someone-else-" + UUID.randomUUID(), Currency.getInstance("KZT")));

        mvc.perform(get("/api/v1/accounts").param("ownerRef", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].currency", containsInAnyOrder("KZT", "USD")));
    }

    @Test
    void ownerWithoutAccountsGetsAnEmptyList() throws Exception {
        mvc.perform(get("/api/v1/accounts").param("ownerRef", "nobody-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void missingOwnerRefIs400() throws Exception {
        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theBanksOwnAccountsAreNeverListed() throws Exception {
        String owner = "search-system-" + UUID.randomUUID();
        accounts.create(NewAccount.system(owner, Currency.getInstance("KZT")));

        mvc.perform(get("/api/v1/accounts").param("ownerRef", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
