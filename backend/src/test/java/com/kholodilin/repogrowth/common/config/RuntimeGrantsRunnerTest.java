package com.kholodilin.repogrowth.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RuntimeGrantsRunnerTest {

    @Mock
    JdbcTemplate jdbc;

    @Test
    void grantsDmlToRuntimeRole() {
        RuntimeGrantsRunner runner = new RuntimeGrantsRunner(jdbc, "repogrowth");
        runner.run(new DefaultApplicationArguments());
        verify(jdbc).execute(contains("GRANT USAGE ON SCHEMA public TO repogrowth"));
        verify(jdbc).execute(contains("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES"));
        verify(jdbc).execute(contains("GRANT USAGE, SELECT ON ALL SEQUENCES"));
        verify(jdbc).execute(contains("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES"));
        verify(jdbc).execute(contains("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES"));
    }

    @Test
    void skipsBlankRuntimeUser() {
        new RuntimeGrantsRunner(jdbc, "  ").run(new DefaultApplicationArguments());
        new RuntimeGrantsRunner(jdbc, null).run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnsafeRoleName() {
        RuntimeGrantsRunner runner = new RuntimeGrantsRunner(jdbc, "bad;drop");
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).execute(contains("GRANT"));
    }
}
