package com.redis.kafka.connect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.util.Assert;

import com.redis.kafka.connect.common.RedisConfigDef;
import com.redis.kafka.connect.source.DataStructureConverter;
import com.redis.kafka.connect.source.RedisKeysSourceConfigDef;
import com.redis.kafka.connect.source.RedisKeysSourceTask;
import com.redis.kafka.connect.source.RedisStreamSourceConfig;
import com.redis.kafka.connect.source.RedisStreamSourceConfigDef;
import com.redis.kafka.connect.source.RedisStreamSourceTask;
import com.redis.lettucemod.api.sync.RedisModulesCommands;
import com.redis.spring.batch.RedisItemWriter;
import com.redis.spring.batch.RedisItemWriter.WriterBuilder;
import com.redis.spring.batch.common.DataStructure;
import com.redis.spring.batch.common.Utils;
import com.redis.spring.batch.reader.GeneratorItemReader;
import com.redis.spring.batch.reader.GeneratorItemReader.Type;
import com.redis.spring.batch.reader.LiveRedisItemReader;

import io.lettuce.core.models.stream.PendingMessages;

abstract class AbstractSourceIntegrationTests extends AbstractIntegrationTests {

	private static final Logger log = LoggerFactory.getLogger(AbstractSourceIntegrationTests.class);

	private RedisStreamSourceTask streamSourceTask;
	private RedisKeysSourceTask keysSourceTask;

	@BeforeEach
	public void createTask() {
		streamSourceTask = new RedisStreamSourceTask();
		keysSourceTask = new RedisKeysSourceTask();
	}

	// Used to initialize a task with a previous connect offset (as though records
	// had been committed).
	void initializeTask(String id) throws Exception {
		streamSourceTask.initialize(new SourceTaskContext() {
			@Override
			public OffsetStorageReader offsetStorageReader() {
				return new OffsetStorageReader() {
					@Override
					public <T> Map<Map<String, T>, Map<String, Object>> offsets(Collection<Map<String, T>> partitions) {
						throw new UnsupportedOperationException("OffsetStorageReader.offsets()");
					}

					@Override
					public <T> Map<String, Object> offset(Map<String, T> partition) {
						return Collections.singletonMap(RedisStreamSourceTask.OFFSET_FIELD, id);
					}
				};
			}

			@Override
			public Map<String, String> configs() {
				throw new UnsupportedOperationException("SourceTaskContext.configs()");
			}
		});
		keysSourceTask.initialize(new SourceTaskContext() {

			@Override
			public OffsetStorageReader offsetStorageReader() {
				return null;
			}

			@Override
			public Map<String, String> configs() {
				throw new UnsupportedOperationException("SourceTaskContext.configs()");
			}
		});
	}

	private void startTask(SourceTask task, String... props) {
		Map<String, String> config = map(props);
		config.put(RedisConfigDef.URI_CONFIG, getRedisServer().getRedisURI());
		task.start(config);

	}

	private void startStreamSourceTask(String... props) {
		startTask(streamSourceTask, props);
	}

	private void startKeysSourceTask(String... props) {
		startTask(keysSourceTask, props);
	}

	protected Map<String, String> map(String... args) {
		Assert.notNull(args, "Args cannot be null");
		Assert.isTrue(args.length % 2 == 0, "Args length is not a multiple of 2");
		Map<String, String> body = new LinkedHashMap<>();
		for (int index = 0; index < args.length / 2; index++) {
			body.put(args[index * 2], args[index * 2 + 1]);
		}
		return body;
	}

	@AfterEach
	public void teardown() {
		keysSourceTask.stop();
		streamSourceTask.stop();
	}

	@Test
	void pollStreamAtMostOnce() throws InterruptedException {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream, RedisStreamSourceConfigDef.STREAM_DELIVERY_CONFIG,
				RedisStreamSourceConfig.STREAM_DELIVERY_AT_MOST_ONCE);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		String id1 = connection.sync().xadd(stream, body);
		String id2 = connection.sync().xadd(stream, body);
		String id3 = connection.sync().xadd(stream, body);
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> sourceRecords.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, sourceRecords.size());
		assertEquals(id1, body, stream, topicPrefix + stream, sourceRecords.get(0));
		assertEquals(id2, body, stream, topicPrefix + stream, sourceRecords.get(1));
		assertEquals(id3, body, stream, topicPrefix + stream, sourceRecords.get(2));
		PendingMessages pendingMsgs = connection.sync().xpending(stream,
				RedisStreamSourceConfigDef.STREAM_CONSUMER_GROUP_DEFAULT);
		Assertions.assertEquals(0, pendingMsgs.getCount(), "pending messages");
	}

	@Test
	void pollStreamAtLeastOnce() throws InterruptedException {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		String id1 = connection.sync().xadd(stream, body);
		String id2 = connection.sync().xadd(stream, body);
		String id3 = connection.sync().xadd(stream, body);
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> sourceRecords.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, sourceRecords.size());
		assertEquals(id1, body, stream, topicPrefix + stream, sourceRecords.get(0));
		assertEquals(id2, body, stream, topicPrefix + stream, sourceRecords.get(1));
		assertEquals(id3, body, stream, topicPrefix + stream, sourceRecords.get(2));
		PendingMessages pendingMsgsBeforeCommit = connection.sync().xpending(stream,
				RedisStreamSourceConfigDef.STREAM_CONSUMER_GROUP_DEFAULT);
		Assertions.assertEquals(3, pendingMsgsBeforeCommit.getCount(), "pending messages before commit");
		streamSourceTask.commitRecord(sourceRecords.get(0), new RecordMetadata(null, 0, 0, 0, null, 0, 0));
		streamSourceTask.commitRecord(sourceRecords.get(1), new RecordMetadata(null, 0, 0, 0, null, 0, 0));
		streamSourceTask.commit();
		PendingMessages pendingMsgsAfterCommit = connection.sync().xpending(stream,
				RedisStreamSourceConfigDef.STREAM_CONSUMER_GROUP_DEFAULT);
		Assertions.assertEquals(1, pendingMsgsAfterCommit.getCount(), "pending messages after commit");
	}

	@Test
	void pollStreamAtLeastOnceRecover() throws InterruptedException {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> sourceRecords.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, sourceRecords.size());

		List<SourceRecord> recoveredRecords = new ArrayList<>();
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);

		// create a new task, same config
		createTask();
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);

		Awaitility.await().until(() -> recoveredRecords.addAll(streamSourceTask.poll()));
		Awaitility.await().until(() -> !recoveredRecords.addAll(streamSourceTask.poll()));

		Assertions.assertEquals(6, recoveredRecords.size());
	}

	@Test
	void pollStreamAtLeastOnceRecoverUncommitted() throws InterruptedException {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		String id3 = connection.sync().xadd(stream, body);
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> sourceRecords.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, sourceRecords.size());
		streamSourceTask.commitRecord(sourceRecords.get(0), new RecordMetadata(null, 0, 0, 0, null, 0, 0));
		streamSourceTask.commitRecord(sourceRecords.get(1), new RecordMetadata(null, 0, 0, 0, null, 0, 0));
		streamSourceTask.commit();

		List<SourceRecord> recoveredRecords = new ArrayList<>();
		String id4 = connection.sync().xadd(stream, body);
		String id5 = connection.sync().xadd(stream, body);
		String id6 = connection.sync().xadd(stream, body);

		// create a new task, same config
		createTask();
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);

		// Wait until task.poll() doesn't return any more records
		Awaitility.await().until(() -> recoveredRecords.addAll(streamSourceTask.poll()));
		Awaitility.await().until(() -> !recoveredRecords.addAll(streamSourceTask.poll()));
		List<String> recoveredIds = recoveredRecords.stream().map(SourceRecord::key).map(String::valueOf)
				.collect(Collectors.toList());
		Assertions.assertEquals(Arrays.<String>asList(id3, id4, id5, id6), recoveredIds, "recoveredIds");
	}

	@Test
	void pollStreamAtLeastOnceRecoverFromOffset() throws Exception {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		String id1 = connection.sync().xadd(stream, body);
		log.info("ID1: " + id1);
		String id2 = connection.sync().xadd(stream, body);
		log.info("ID2: " + id2);
		String id3 = connection.sync().xadd(stream, body);
		log.info("ID3: " + id3);
		List<SourceRecord> records = new ArrayList<>();
		Awaitility.await().until(() -> records.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, records.size());

		List<SourceRecord> recoveredRecords = new ArrayList<>();
		String id4 = connection.sync().xadd(stream, body);
		log.info("ID4: " + id4);
		String id5 = connection.sync().xadd(stream, body);
		log.info("ID5: " + id5);
		String id6 = connection.sync().xadd(stream, body);
		log.info("ID6: " + id6);

		// create a new task, same config
		createTask();
		// this means connect committed records, but StreamSourceTask didn't get a
		// chance to ack first
		initializeTask(id3);
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream);

		// Wait until task.poll() doesn't return any more records
		Awaitility.await().until(() -> recoveredRecords.addAll(streamSourceTask.poll()));
		Awaitility.await().until(() -> !recoveredRecords.addAll(streamSourceTask.poll()));

		List<String> recoveredIds = recoveredRecords.stream().map(SourceRecord::key).map(String::valueOf)
				.collect(Collectors.toList());
		Assertions.assertEquals(Arrays.<String>asList(id4, id5, id6), recoveredIds, "recoveredIds");
	}

	@Test
	void pollStreamAtMostOnceRecover() throws InterruptedException {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream, RedisStreamSourceConfigDef.STREAM_DELIVERY_CONFIG,
				RedisStreamSourceConfig.STREAM_DELIVERY_AT_MOST_ONCE);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> sourceRecords.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, sourceRecords.size());

		List<SourceRecord> recoveredRecords = new ArrayList<>();
		String id4 = connection.sync().xadd(stream, body);
		String id5 = connection.sync().xadd(stream, body);
		String id6 = connection.sync().xadd(stream, body);

		// create a new task, same config
		createTask();
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream, RedisStreamSourceConfigDef.STREAM_DELIVERY_CONFIG,
				RedisStreamSourceConfig.STREAM_DELIVERY_AT_MOST_ONCE);

		// Wait until task.poll() doesn't return any more records
		Awaitility.await().until(() -> recoveredRecords.addAll(streamSourceTask.poll()));
		Awaitility.await().until(() -> !recoveredRecords.addAll(streamSourceTask.poll()));
		List<String> recoveredIds = recoveredRecords.stream().map(SourceRecord::key).map(String::valueOf)
				.collect(Collectors.toList());
		Assertions.assertEquals(Arrays.asList(id4, id5, id6), recoveredIds, "recoveredIds");
	}

	@Test
	void pollStreamRecoverAtLeastOnceToAtMostOnce() throws InterruptedException {
		String stream = "stream1";
		String topicPrefix = "testprefix-";
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream, RedisStreamSourceConfigDef.STREAM_DELIVERY_CONFIG,
				RedisStreamSourceConfig.STREAM_DELIVERY_AT_LEAST_ONCE);
		String field1 = "field1";
		String value1 = "value1";
		String field2 = "field2";
		String value2 = "value2";
		Map<String, String> body = map(field1, value1, field2, value2);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		connection.sync().xadd(stream, body);
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> sourceRecords.addAll(streamSourceTask.poll()));
		Assertions.assertEquals(3, sourceRecords.size());

		List<SourceRecord> recoveredRecords = new ArrayList<>();
		String id4 = connection.sync().xadd(stream, body);
		String id5 = connection.sync().xadd(stream, body);
		String id6 = connection.sync().xadd(stream, body);

		// create a new task, same config except AT_MOST_ONCE
		createTask();
		startStreamSourceTask(RedisStreamSourceConfigDef.TOPIC_CONFIG,
				topicPrefix + RedisStreamSourceConfigDef.TOKEN_STREAM, RedisStreamSourceConfigDef.STREAM_NAME_CONFIG,
				stream, RedisStreamSourceConfigDef.STREAM_DELIVERY_CONFIG,
				RedisStreamSourceConfig.STREAM_DELIVERY_AT_MOST_ONCE);

		// Wait until task.poll() doesn't return any more records
		Awaitility.await().until(() -> recoveredRecords.addAll(streamSourceTask.poll()));
		Awaitility.await().until(() -> !recoveredRecords.addAll(streamSourceTask.poll()));
		List<String> recoveredIds = recoveredRecords.stream().map(SourceRecord::key).map(String::valueOf)
				.collect(Collectors.toList());
		Assertions.assertEquals(Arrays.asList(id4, id5, id6), recoveredIds, "recoveredIds");

		PendingMessages pending = connection.sync().xpending(stream,
				RedisStreamSourceConfigDef.STREAM_CONSUMER_GROUP_DEFAULT);
		Assertions.assertEquals(0, pending.getCount(), "pending message count");
	}

	private void assertEquals(String expectedId, Map<String, String> expectedBody, String expectedStream,
			String expectedTopic, SourceRecord record) {
		Struct struct = (Struct) record.value();
		Assertions.assertEquals(expectedId, struct.get("id"));
		Assertions.assertEquals(expectedBody, struct.get("body"));
		Assertions.assertEquals(expectedStream, struct.get("stream"));
		Assertions.assertEquals(expectedTopic, record.topic());
	}

	@Test
	void pollKeys() throws Exception {
		enableKeyspaceNotifications();
		String topic = "mytopic";
		startKeysSourceTask(RedisKeysSourceConfigDef.TOPIC_CONFIG, topic, RedisKeysSourceConfigDef.IDLE_TIMEOUT_CONFIG,
				"3000");
		LiveRedisItemReader<String, String, DataStructure<String>> reader = keysSourceTask.getReader();
		Awaitility.await().until(reader::isOpen);
		int count = 100;
		GeneratorItemReader generator = new GeneratorItemReader();
		generator.setTypes(Type.values());
		generator.setMaxItemCount(count);
		RedisItemWriter<String, String, DataStructure<String>> writer = new WriterBuilder(client).dataStructure();
		JobRepository jobRepository = Utils.inMemoryJobRepository();
		SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
		jobLauncher.setJobRepository(jobRepository);
		jobLauncher.afterPropertiesSet();
		ResourcelessTransactionManager transactionManager = new ResourcelessTransactionManager();
		StepBuilderFactory stepBuilderFactory = new StepBuilderFactory(jobRepository, transactionManager);
		SimpleStepBuilder<DataStructure<String>, DataStructure<String>> step = stepBuilderFactory
				.get("pollKeys-" + redisURI).chunk(1);
		step.reader(generator);
		step.writer(writer);
		JobBuilderFactory jobBuilderFactory = new JobBuilderFactory(jobRepository);
		Job job = jobBuilderFactory.get("pollKeys-" + redisURI).start(step.build()).build();
		jobLauncher.run(job, new JobParameters());
		List<SourceRecord> sourceRecords = new ArrayList<>();
		Awaitility.await().until(() -> {
			sourceRecords.addAll(keysSourceTask.poll());
			return sourceRecords.size() >= count;
		});
		for (SourceRecord record : sourceRecords) {
			Assertions.assertEquals(topic, record.topic());
			Compare compare = values((Struct) record.value());
			if (compare != null) {
				Assertions.assertEquals(compare.expected, compare.actual);
			}
		}

//		DataStructure<String> stringDS = new DataStructure<>();
//		stringDS.setKey(stringKey);
//		stringDS.setValue(stringValue);
//		stringDS.setType(DataStructure.STRING);
//		DataStructure<String> hashDS = new DataStructure<>();
//		hashDS.setKey(hashKey);
//		hashDS.setValue(hashValue);
//		hashDS.setType(DataStructure.HASH);
//		Assertions.assertEquals(converter.apply(stringDS), sourceRecords.get(0).value());
//		Assertions.assertEquals(converter.apply(hashDS), sourceRecords.get(1).value());
	}

	private static class Compare {

		private final Object expected;
		private final Object actual;

		public Compare(Object expected, Object actual) {
			this.expected = expected;
			this.actual = actual;
		}

	}

	private Compare values(Struct struct) {
		String key = struct.getString(DataStructureConverter.FIELD_KEY);
		String type = struct.getString(DataStructureConverter.FIELD_TYPE);
		Assertions.assertEquals(connection.sync().type(key), type);
		RedisModulesCommands<String, String> commands = connection.sync();
		switch (type) {
		case DataStructure.HASH:
			return compare(commands.hgetall(key), struct.getMap(DataStructureConverter.FIELD_HASH));
		case DataStructure.JSON:
			return compare(commands.jsonGet(key), struct.getString(DataStructureConverter.FIELD_JSON));
		case DataStructure.LIST:
			return compare(commands.lrange(key, 0, -1), struct.getArray(DataStructureConverter.FIELD_LIST));
		case DataStructure.SET:
			return compare(commands.smembers(key), new HashSet<>(struct.getArray(DataStructureConverter.FIELD_SET)));
		case DataStructure.STRING:
			return compare(commands.get(key), struct.getString(DataStructureConverter.FIELD_STRING));
		case DataStructure.ZSET:
			return compare(DataStructureConverter.zsetMap(commands.zrangeWithScores(key, 0, -1)),
					struct.getMap(DataStructureConverter.FIELD_ZSET));
		default:
			return null;
		}
	}

	private static Compare compare(Object expected, Object actual) {
		return new Compare(expected, actual);
	}

}
