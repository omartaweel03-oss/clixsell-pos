package com.clixsell.pos.user;

/** Data for a new Store Admin or Cashier account. */
public record NewUserRequest(String username, String fullName, String password) {
}
