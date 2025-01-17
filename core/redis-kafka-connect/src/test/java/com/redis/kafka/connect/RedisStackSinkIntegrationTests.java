package com.redis.kafka.connect;

import com.redis.testcontainers.RedisServer;
import com.redis.testcontainers.RedisStackContainer;

class RedisStackSinkIntegrationTests extends AbstractSinkIntegrationTests {

	private static final RedisStackContainer container = new RedisStackContainer(
			RedisStackContainer.DEFAULT_IMAGE_NAME.withTag(RedisStackContainer.DEFAULT_TAG));

	@Override
	protected RedisServer getRedisServer() {
		return container;
	}

}
