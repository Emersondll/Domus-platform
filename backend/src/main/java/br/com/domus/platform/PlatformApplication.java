package br.com.domus.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Ponto de entrada do monólito modular da plataforma de portaria virtual. */
@SpringBootApplication
public class PlatformApplication {

    /**
     * Inicia a aplicação Spring Boot.
     *
     * @param args args
     */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
