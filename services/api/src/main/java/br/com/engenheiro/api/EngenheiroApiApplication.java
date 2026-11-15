package br.com.engenheiro.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EngenheiroApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngenheiroApiApplication.class, args);
    }
}
