package br.com.portaria.platform.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Relógio injetável (regras de validade testáveis) e habilitação das tarefas agendadas. */
@Configuration
@EnableScheduling
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
