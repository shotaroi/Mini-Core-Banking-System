package com.shotaroi.bank.security;

public record AuthenticatedUser(Long id, String email) {

    public static AuthenticatedUser from(Object principal) {
        if (principal instanceof AuthenticatedUser au) {
            return au;
        }
        throw new IllegalArgumentException("Principal is not AuthenticatedUser");
    }
}
