/*
 * Copyright © 2021 Redis
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redis.kafka.connect.sink;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import com.redis.kafka.connect.common.RedisConfig;

public class RedisSinkConfig extends RedisConfig {

	public enum RedisCommand {
		HSET, JSONSET, TSADD, SET, XADD, LPUSH, RPUSH, SADD, ZADD, DEL
	}

	private final Charset charset;
	private final RedisCommand command;
	private final String keyspace;
	private final String separator;
	private final boolean multiexec;
	private final int waitReplicas;
	private final Duration waitTimeout;

	public RedisSinkConfig(Map<?, ?> originals) {
		super(new RedisSinkConfigDef(), originals);
		String charsetName = getString(RedisSinkConfigDef.CHARSET_CONFIG).trim();
		charset = Charset.forName(charsetName);
		command = RedisCommand.valueOf(getString(RedisSinkConfigDef.COMMAND_CONFIG));
		keyspace = getString(RedisSinkConfigDef.KEY_CONFIG).trim();
		separator = getString(RedisSinkConfigDef.SEPARATOR_CONFIG).trim();
		multiexec = Boolean.TRUE.equals(getBoolean(RedisSinkConfigDef.MULTIEXEC_CONFIG));
		waitReplicas = getInt(RedisSinkConfigDef.WAIT_REPLICAS_CONFIG);
		waitTimeout = Duration.ofMillis(getLong(RedisSinkConfigDef.WAIT_TIMEOUT_CONFIG));
	}

	public Charset getCharset() {
		return charset;
	}

	public RedisCommand getCommand() {
		return command;
	}

	public String getKeyspace() {
		return keyspace;
	}

	public String getSeparator() {
		return separator;
	}

	public boolean isMultiexec() {
		return multiexec;
	}

	public int getWaitReplicas() {
		return waitReplicas;
	}

	public Duration getWaitTimeout() {
		return waitTimeout;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
				+ Objects.hash(charset, keyspace, separator, multiexec, command, waitReplicas, waitTimeout);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		RedisSinkConfig other = (RedisSinkConfig) obj;
		return Objects.equals(charset, other.charset) && Objects.equals(keyspace, other.keyspace)
				&& Objects.equals(separator, other.separator) && multiexec == other.multiexec
				&& command == other.command && waitReplicas == other.waitReplicas && waitTimeout == other.waitTimeout;
	}

}
