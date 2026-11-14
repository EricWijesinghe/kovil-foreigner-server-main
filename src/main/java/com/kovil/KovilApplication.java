package com.kovil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@ConfigurationPropertiesScan
@EnableFeignClients
@EnableMethodSecurity
@EnableScheduling
@SpringBootApplication
public class KovilApplication {

	public static void main(String[] args) {
		SpringApplication.run(KovilApplication.class, args);
	}

}
