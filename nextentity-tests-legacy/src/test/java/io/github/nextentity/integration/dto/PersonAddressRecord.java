package io.github.nextentity.integration.dto;

public record PersonAddressRecord(
        Long id,
        String name,
        String street,
        String city,
        String zipCode
) {
}
