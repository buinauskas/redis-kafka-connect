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
package com.redis.kafka.connect.source;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;

import com.redis.kafka.connect.common.ManifestVersionProvider;
import com.redis.spring.batch.RedisItemReader.LiveReaderBuilder;
import com.redis.spring.batch.RedisItemReader.ScanReaderBuilder;
import com.redis.spring.batch.common.DataStructure;
import com.redis.spring.batch.reader.LiveRedisItemReader;

import io.lettuce.core.AbstractRedisClient;

public class RedisKeysSourceTask extends SourceTask {

	public static final Schema KEY_SCHEMA = Schema.STRING_SCHEMA;

	/**
	 * The offsets that have been processed and that are to be acknowledged by the
	 * reader in {@link RedisKeysSourceTask#commit()}.
	 */
	private final List<Map<String, ?>> sourceOffsets = new ArrayList<>();
	private final DataStructureConverter converter = new DataStructureConverter();
	private final Clock clock;
	private String topic;
	private int batchSize;
	private LiveRedisItemReader<String, String, DataStructure<String>> reader;
	private AbstractRedisClient client;

	public RedisKeysSourceTask() {
		this(Clock.systemDefaultZone());
	}

	public RedisKeysSourceTask(Clock clock) {
		this.clock = clock;
	}

	@Override
	public String version() {
		return ManifestVersionProvider.getVersion();
	}

	public LiveRedisItemReader<String, String, DataStructure<String>> getReader() {
		return reader;
	}

	@Override
	public void start(Map<String, String> props) {
		RedisKeysSourceConfig config = new RedisKeysSourceConfig(props);
		this.topic = config.getTopicName();
		this.batchSize = Math.toIntExact(config.getBatchSize());
		this.client = config.client();
		int database = config.uri().getDatabase();
		String[] keyPatterns = config.getKeyPatterns().toArray(new String[0]);
		LiveReaderBuilder builder = new ScanReaderBuilder(client).live().poolOptions(config.poolOptions())
				.database(database).keyPatterns(keyPatterns).chunkSize(batchSize);
		if (!config.getIdleTimeout().isNegative() && !config.getIdleTimeout().isZero()) {
			builder.idleTimeout(config.getIdleTimeout());
		}
		this.reader = builder.dataStructure();
		try {
			reader.open(new ExecutionContext());
		} catch (ItemStreamException e) {
			throw new RetriableException("Could not open reader", e);
		}
	}

	private void addSourceOffset(Map<String, ?> sourceOffset) {
		sourceOffsets.add(sourceOffset);
	}

	@Deprecated
	@Override
	public void commitRecord(SourceRecord sourceRecord) throws InterruptedException {
		Map<String, ?> currentOffset = sourceRecord.sourceOffset();
		if (currentOffset != null) {
			addSourceOffset(currentOffset);
		}
	}

	@Override
	public void commit() throws InterruptedException {
		// do nothing
	}

	@Override
	public void stop() {
		if (reader != null) {
			reader.close();
			reader = null;
		}
		if (client != null) {
			client.shutdown();
			client.getResources().shutdown();
			client = null;
		}
	}

	private SourceRecord convert(DataStructure<String> input) {
		Map<String, ?> partition = new HashMap<>();
		Map<String, ?> offset = new HashMap<>();
		String key = input.getKey();
		long epoch = clock.instant().toEpochMilli();
		return new SourceRecord(partition, offset, topic, null, KEY_SCHEMA, key, DataStructureConverter.VALUE_SCHEMA,
				converter.apply(input), epoch);
	}

	@Override
	public List<SourceRecord> poll() {
		// TODO: return heartbeat if no records
		return reader.read(batchSize).stream().map(this::convert).collect(Collectors.toList());
	}

}
