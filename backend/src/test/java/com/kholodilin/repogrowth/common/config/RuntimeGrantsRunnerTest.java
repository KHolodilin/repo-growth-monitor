package com.kholodilin.repogrowth.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

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
    }

    @Test
    void rejectsUnsafeRoleName() {
        RuntimeGrantsRunner runner = new RuntimeGrantsRunner(jdbc, "bad;drop");
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
    }
}
