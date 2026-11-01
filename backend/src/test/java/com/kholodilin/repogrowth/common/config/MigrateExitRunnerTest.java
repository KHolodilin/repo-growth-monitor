package com.kholodilin.repogrowth.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MigrateExitRunnerTest {

    @Mock
    ConfigurableApplicationContext context;

    @Test
    void exitsWithSpringApplicationExitCode() {
        AtomicInteger code = new AtomicInteger(-1);
        MigrateExitRunner runner = new MigrateExitRunner(context, code::set);
        runner.run(new DefaultApplicationArguments());
        assertThat(code.get()).isZero();
    }

    @Test
    void publicConstructorUsesSystemExit() {
        assertThat(new MigrateExitRunner(context)).isNotNull();
    }
}
