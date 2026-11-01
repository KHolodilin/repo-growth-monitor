package com.kholodilin.repogrowth.auth.config;

import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MockUserSyncRunner implements ApplicationRunner {

    private final AuthProvider provider;
    private final MockUserCatalog catalog;

    public MockUserSyncRunner(AuthProvider provider, MockUserCatalog catalog) {
        this.provider = provider;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (provider == AuthProvider.MOCK) {
            catalog.syncMissingUsers();
        }
    }
}
