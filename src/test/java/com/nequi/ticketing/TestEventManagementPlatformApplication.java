package com.nequi.ticketing;

import org.springframework.boot.SpringApplication;

public class TestEventManagementPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.from(EventManagementPlatformApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
