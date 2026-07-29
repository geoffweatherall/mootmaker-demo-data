package com.mootmaker.tools.databaserepair;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

import module java.base;

/**
 * Minimal in-memory test double covering only ListUsers, paginating {@code page.size()} users per
 * call (regardless of the requested limit) so pagination itself is exercised by tests that supply
 * more than one page.
 */
class FakeCognitoIdentityProviderClient implements CognitoIdentityProviderClient {

    private final List<List<UserType>> pages;

    FakeCognitoIdentityProviderClient(final List<List<UserType>> pages) {
        this.pages = pages;
    }

    @Override
    public String serviceName() {
        return "cognito-idp";
    }

    @Override
    public void close() {
    }

    @Override
    public ListUsersResponse listUsers(final ListUsersRequest request) {
        final int pageIndex = request.paginationToken() == null ? 0 : Integer.parseInt(request.paginationToken());
        final List<UserType> page = pages.get(pageIndex);
        final boolean hasNextPage = pageIndex + 1 < pages.size();
        return ListUsersResponse.builder()
                .users(page)
                .paginationToken(hasNextPage ? String.valueOf(pageIndex + 1) : null)
                .build();
    }
}
