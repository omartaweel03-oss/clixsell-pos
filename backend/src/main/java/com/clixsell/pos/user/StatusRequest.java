package com.clixsell.pos.user;

import com.clixsell.pos.common.AccountStatus;

/** Body of a request that activates or deactivates a store or a user. */
public record StatusRequest(AccountStatus status) {
}
