package com.pretriage.backend;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApplicationTests {

	@Autowired
	private ServletContext servletContext;

	@Test
	void contextLoads() {
		ServerContainer container = assertInstanceOf(ServerContainer.class,
				servletContext.getAttribute(ServerContainer.class.getName()));
		assertEquals(256 * 1024, container.getDefaultMaxBinaryMessageBufferSize());
		assertEquals(16 * 1024, container.getDefaultMaxTextMessageBufferSize());
		assertEquals(300_000L, container.getDefaultMaxSessionIdleTimeout());
	}

}
