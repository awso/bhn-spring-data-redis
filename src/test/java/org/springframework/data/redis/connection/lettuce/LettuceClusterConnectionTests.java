/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.lettuce;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.springframework.data.redis.connection.ClusterTestVariables.*;
import static org.springframework.data.redis.connection.RedisGeoCommands.DistanceUnit.*;
import static org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs.*;
import static org.springframework.data.redis.core.ScanOptions.*;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.ClusterConnectionTests;
import org.springframework.data.redis.connection.ClusterSlotHashUtil;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.DefaultSortParameters;
import org.springframework.data.redis.connection.DefaultTuple;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisListCommands.Position;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisStringCommands.BitOperation;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.RedisZSetCommands.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Tuple;
import org.springframework.data.redis.connection.jedis.JedisConverters;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.test.util.MinimumRedisVersionRule;
import org.springframework.data.redis.test.util.RedisClusterRule;
import org.springframework.test.annotation.IfProfileValue;

import com.lambdaworks.redis.RedisURI.Builder;
import com.lambdaworks.redis.api.sync.RedisHLLCommands;
import com.lambdaworks.redis.cluster.RedisClusterClient;
import com.lambdaworks.redis.cluster.api.sync.RedisAdvancedClusterCommands;

/**
 * @author Christoph Strobl
 * @author Mark Paluch
 */
public class LettuceClusterConnectionTests implements ClusterConnectionTests {

	static final byte[] KEY_1_BYTES = LettuceConverters.toBytes(KEY_1);
	static final byte[] KEY_2_BYTES = LettuceConverters.toBytes(KEY_2);
	static final byte[] KEY_3_BYTES = LettuceConverters.toBytes(KEY_3);

	static final byte[] SAME_SLOT_KEY_1_BYTES = LettuceConverters.toBytes(SAME_SLOT_KEY_1);
	static final byte[] SAME_SLOT_KEY_2_BYTES = LettuceConverters.toBytes(SAME_SLOT_KEY_2);
	static final byte[] SAME_SLOT_KEY_3_BYTES = LettuceConverters.toBytes(SAME_SLOT_KEY_3);

	static final byte[] VALUE_1_BYTES = LettuceConverters.toBytes(VALUE_1);
	static final byte[] VALUE_2_BYTES = LettuceConverters.toBytes(VALUE_2);
	static final byte[] VALUE_3_BYTES = LettuceConverters.toBytes(VALUE_3);

	static final GeoLocation<String> ARIGENTO = new GeoLocation<String>("arigento", POINT_ARIGENTO);
	static final GeoLocation<String> CATANIA = new GeoLocation<String>("catania", POINT_CATANIA);
	static final GeoLocation<String> PALERMO = new GeoLocation<String>("palermo", POINT_PALERMO);

	static final GeoLocation<byte[]> ARIGENTO_BYTES = new GeoLocation<byte[]>("arigento".getBytes(Charset.forName("UTF-8")),
			POINT_ARIGENTO);
	static final GeoLocation<byte[]> CATANIA_BYTES = new GeoLocation<byte[]>("catania".getBytes(Charset.forName("UTF-8")),
			POINT_CATANIA);
	static final GeoLocation<byte[]> PALERMO_BYTES = new GeoLocation<byte[]>("palermo".getBytes(Charset.forName("UTF-8")),
			POINT_PALERMO);

	RedisClusterClient client;
	RedisAdvancedClusterCommands<String, String> nativeConnection;
	LettuceClusterConnection clusterConnection;

	public static @ClassRule RedisClusterRule clusterAvailable = new RedisClusterRule();

	/**
	 * Check for specific Redis Versions
	 */
	public @Rule MinimumRedisVersionRule version = new MinimumRedisVersionRule();

	@Before
	public void setUp() {

		client = RedisClusterClient.create(LettuceTestClientResources.getSharedClientResources(),
				Builder.redis(CLUSTER_HOST, MASTER_NODE_1_PORT).withTimeout(500, TimeUnit.MILLISECONDS).build());
		nativeConnection = client.connect().sync();
		clusterConnection = new LettuceClusterConnection(client);
	}

	@After
	public void tearDown() throws InterruptedException {

		clusterConnection.flushDb();
		nativeConnection.getStatefulConnection().close();
		clusterConnection.close();
		client.shutdown(0, 0, TimeUnit.MILLISECONDS);
	}

	@Test // DATAREDIS-315
	public void shouldAllowSettingAndGettingValues() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES);
		assertThat(clusterConnection.get(KEY_1_BYTES), is(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void delShouldRemoveSingleKeyCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.del(KEY_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), nullValue());
	}

	@Test // DATAREDIS-315
	public void delShouldRemoveMultipleKeysCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		clusterConnection.del(KEY_1_BYTES);
		clusterConnection.del(KEY_2_BYTES);

		assertThat(nativeConnection.get(KEY_1), nullValue());
		assertThat(nativeConnection.get(KEY_2), nullValue());
	}

	@Test // DATAREDIS-315
	public void delShouldRemoveMultipleKeysOnSameSlotCorrectly() {

		nativeConnection.set(SAME_SLOT_KEY_1, VALUE_1);
		nativeConnection.set(SAME_SLOT_KEY_2, VALUE_2);

		clusterConnection.del(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.get(SAME_SLOT_KEY_1), nullValue());
		assertThat(nativeConnection.get(SAME_SLOT_KEY_2), nullValue());
	}

	@Test // DATAREDIS-315
	public void typeShouldReadKeyTypeCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);
		nativeConnection.hmset(KEY_3, Collections.singletonMap(KEY_1, VALUE_1));

		assertThat(clusterConnection.type(KEY_1_BYTES), is(DataType.SET));
		assertThat(clusterConnection.type(KEY_2_BYTES), is(DataType.STRING));
		assertThat(clusterConnection.type(KEY_3_BYTES), is(DataType.HASH));
	}

	@Test // DATAREDIS-315
	public void keysShouldReturnAllKeys() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.keys(LettuceConverters.toBytes("*")), hasItems(KEY_1_BYTES, KEY_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void keysShouldReturnAllKeysForSpecificNode() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		Set<byte[]> keysOnNode = clusterConnection.keys(new RedisClusterNode("127.0.0.1", 7379, null),
				JedisConverters.toBytes("*"));

		assertThat(keysOnNode, hasItems(KEY_2_BYTES));
		assertThat(keysOnNode, not(hasItems(KEY_1_BYTES)));
	}

	@Test // DATAREDIS-315
	public void randomKeyShouldReturnCorrectlyWhenKeysAvailable() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.randomKey(), notNullValue());
	}

	@Test // DATAREDIS-315
	public void randomKeyShouldReturnNullWhenNoKeysAvailable() {
		assertThat(clusterConnection.randomKey(), nullValue());
	}

	@Test // DATAREDIS-315
	public void rename() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.rename(KEY_1_BYTES, KEY_2_BYTES);

		assertThat(nativeConnection.exists(KEY_1), is(false));
		assertThat(nativeConnection.get(KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void renameSameKeysOnSameSlot() {

		nativeConnection.set(SAME_SLOT_KEY_1, VALUE_1);

		clusterConnection.rename(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.exists(SAME_SLOT_KEY_1), is(false));
		assertThat(nativeConnection.get(SAME_SLOT_KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void renameNXWhenTargetKeyDoesNotExist() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.renameNX(KEY_1_BYTES, KEY_2_BYTES), is(Boolean.TRUE));

		assertThat(nativeConnection.exists(KEY_1), is(false));
		assertThat(nativeConnection.get(KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void renameNXWhenTargetKeyDoesExist() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.renameNX(KEY_1_BYTES, KEY_2_BYTES), is(Boolean.FALSE));

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.get(KEY_2), is(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void renameNXWhenOnSameSlot() {

		nativeConnection.set(SAME_SLOT_KEY_1, VALUE_1);

		assertThat(clusterConnection.renameNX(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES), is(Boolean.TRUE));

		assertThat(nativeConnection.exists(SAME_SLOT_KEY_1), is(false));
		assertThat(nativeConnection.get(SAME_SLOT_KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void expireShouldBeSetCorreclty() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.expire(KEY_1_BYTES, 5);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void pExpireShouldBeSetCorreclty() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.pExpire(KEY_1_BYTES, 5000);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void expireAtShouldBeSetCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.expireAt(KEY_1_BYTES, System.currentTimeMillis() / 1000 + 5000);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void pExpireAtShouldBeSetCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.pExpireAt(KEY_1_BYTES, System.currentTimeMillis() + 5000);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void persistShouldRemoveTTL() {

		nativeConnection.setex(KEY_1, 10, VALUE_1);

		assertThat(clusterConnection.persist(KEY_1_BYTES), is(Boolean.TRUE));
		assertThat(nativeConnection.ttl(KEY_1), is(-1L));
	}

	@Test(expected = UnsupportedOperationException.class) // DATAREDIS-315
	public void moveShouldNotBeSupported() {
		clusterConnection.move(KEY_1_BYTES, 3);
	}

	@Test // DATAREDIS-315
	public void dbSizeShouldReturnCummulatedDbSize() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.dbSize(), is(2L));
	}

	@Test // DATAREDIS-315
	public void dbSizeForSpecificNodeShouldGetNodeDbSize() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.dbSize(new RedisClusterNode("127.0.0.1", 7379, null)), is(1L));
		assertThat(clusterConnection.dbSize(new RedisClusterNode("127.0.0.1", 7380, null)), is(1L));
		assertThat(clusterConnection.dbSize(new RedisClusterNode("127.0.0.1", 7381, null)), is(0L));
	}

	@Test // DATAREDIS-315
	public void ttlShouldReturnMinusTwoWhenKeyDoesNotExist() {
		assertThat(clusterConnection.ttl(KEY_1_BYTES), is(-2L));
	}

	@Test // DATAREDIS-526
	public void ttlWithTimeUnitShouldReturnMinusTwoWhenKeyDoesNotExist() {
		assertThat(clusterConnection.ttl(KEY_1_BYTES, TimeUnit.HOURS), is(-2L));
	}

	@Test // DATAREDIS-315
	public void ttlShouldReturnMinusOneWhenKeyDoesNotHaveExpirationSet() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.ttl(KEY_1_BYTES), is(-1L));
	}

	@Test // DATAREDIS-526
	public void ttlWithTimeUnitShouldReturnMinusOneWhenKeyDoesNotHaveExpirationSet() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.ttl(KEY_1_BYTES, TimeUnit.SECONDS), is(-1L));
	}

	@Test // DATAREDIS-315
	public void ttlShouldReturnValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.expire(KEY_1, 5);

		assertThat(clusterConnection.ttl(KEY_1_BYTES) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void pTtlShouldReturnMinusTwoWhenKeyDoesNotExist() {
		assertThat(clusterConnection.pTtl(KEY_1_BYTES), is(-2L));
	}

	@Test // DATAREDIS-526
	public void pTtlWithTimeUnitShouldReturnMinusTwoWhenKeyDoesNotExist() {
		assertThat(clusterConnection.pTtl(KEY_1_BYTES, TimeUnit.SECONDS), is(-2L));
	}

	@Test // DATAREDIS-315
	public void pTtlShouldReturnMinusOneWhenKeyDoesNotHaveExpirationSet() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.pTtl(KEY_1_BYTES), is(-1L));
	}

	@Test // DATAREDIS-315
	public void pTtlShouldReturValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.expire(KEY_1, 5);

		assertThat(clusterConnection.pTtl(KEY_1_BYTES) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void sortShouldReturnValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_2, VALUE_1);

		assertThat(clusterConnection.sort(KEY_1_BYTES, new DefaultSortParameters().alpha()),
				hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void sortAndStoreShouldAddSortedValuesValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_2, VALUE_1);

		assertThat(clusterConnection.sort(KEY_1_BYTES, new DefaultSortParameters().alpha(), KEY_2_BYTES), is(1L));
		assertThat(nativeConnection.exists(KEY_2), is(true));
	}

	@Test // DATAREDIS-315
	public void sortAndStoreShouldReturnZeroWhenListDoesNotExist() {
		assertThat(clusterConnection.sort(KEY_1_BYTES, new DefaultSortParameters().alpha(), KEY_2_BYTES), is(0L));
	}

	@Test // DATAREDIS-315
	public void dumpAndRestoreShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		byte[] dumpedValue = clusterConnection.dump(KEY_1_BYTES);
		clusterConnection.restore(KEY_2_BYTES, 0, dumpedValue);

		assertThat(nativeConnection.get(KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void getShouldReturnValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.get(KEY_1_BYTES), is(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void getSetShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		byte[] valueBeforeSet = clusterConnection.getSet(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(valueBeforeSet, is(VALUE_1_BYTES));
		assertThat(nativeConnection.get(KEY_1), is(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void mGetShouldReturnCorrectlyWhenKeysMapToSameSlot() {

		nativeConnection.set(SAME_SLOT_KEY_1, VALUE_1);
		nativeConnection.set(SAME_SLOT_KEY_2, VALUE_2);

		assertThat(clusterConnection.mGet(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES),
				contains(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void mGetShouldReturnCorrectlyWhenKeysDoNotMapToSameSlot() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.mGet(KEY_1_BYTES, KEY_2_BYTES), contains(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void setShouldSetValueCorrectly() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void setNxShouldSetValueCorrectly() {

		clusterConnection.setNX(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void setNxShouldNotSetValueWhenAlreadyExistsInDBCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.setNX(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void setExShouldSetValueCorrectly() {

		clusterConnection.setEx(KEY_1_BYTES, 5, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.ttl(KEY_1) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void pSetExShouldSetValueCorrectly() {

		clusterConnection.pSetEx(KEY_1_BYTES, 5000, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.ttl(KEY_1) > 1, is(true));
	}

	@Test // DATAREDIS-315
	public void mSetShouldWorkWhenKeysMapToSameSlot() {

		Map<byte[], byte[]> map = new LinkedHashMap<byte[], byte[]>();
		map.put(SAME_SLOT_KEY_1_BYTES, VALUE_1_BYTES);
		map.put(SAME_SLOT_KEY_2_BYTES, VALUE_2_BYTES);

		clusterConnection.mSet(map);

		assertThat(nativeConnection.get(SAME_SLOT_KEY_1), is(VALUE_1));
		assertThat(nativeConnection.get(SAME_SLOT_KEY_2), is(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void mSetShouldWorkWhenKeysDoNotMapToSameSlot() {

		Map<byte[], byte[]> map = new LinkedHashMap<byte[], byte[]>();
		map.put(KEY_1_BYTES, VALUE_1_BYTES);
		map.put(KEY_2_BYTES, VALUE_2_BYTES);

		clusterConnection.mSet(map);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.get(KEY_2), is(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void mSetNXShouldReturnTrueIfAllKeysSet() {

		Map<byte[], byte[]> map = new LinkedHashMap<byte[], byte[]>();
		map.put(KEY_1_BYTES, VALUE_1_BYTES);
		map.put(KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(clusterConnection.mSetNX(map), is(true));

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.get(KEY_2), is(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void mSetNXShouldReturnFalseIfNotAllKeysSet() {

		nativeConnection.set(KEY_2, VALUE_3);
		Map<byte[], byte[]> map = new LinkedHashMap<byte[], byte[]>();
		map.put(KEY_1_BYTES, VALUE_1_BYTES);
		map.put(KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(clusterConnection.mSetNX(map), is(false));

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.get(KEY_2), is(VALUE_3));
	}

	@Test // DATAREDIS-315
	public void mSetNXShouldWorkForOnSameSlotKeys() {

		Map<byte[], byte[]> map = new LinkedHashMap<byte[], byte[]>();
		map.put(SAME_SLOT_KEY_1_BYTES, VALUE_1_BYTES);
		map.put(SAME_SLOT_KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(clusterConnection.mSetNX(map), is(true));

		assertThat(nativeConnection.get(SAME_SLOT_KEY_1), is(VALUE_1));
		assertThat(nativeConnection.get(SAME_SLOT_KEY_2), is(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void incrShouldIncreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "1");

		assertThat(clusterConnection.incr(KEY_1_BYTES), is(2L));
	}

	@Test // DATAREDIS-315
	public void incrByFloatShouldIncreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "1");

		assertThat(clusterConnection.incrBy(KEY_1_BYTES, 5.5D), is(6.5D));
	}

	@Test // DATAREDIS-315
	public void incrByShouldIncreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "1");

		assertThat(clusterConnection.incrBy(KEY_1_BYTES, 5), is(6L));
	}

	@Test // DATAREDIS-315
	public void decrShouldDecreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "5");

		assertThat(clusterConnection.decr(KEY_1_BYTES), is(4L));
	}

	@Test // DATAREDIS-315
	public void decrByShouldDecreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "5");

		assertThat(clusterConnection.decrBy(KEY_1_BYTES, 4), is(1L));
	}

	@Test // DATAREDIS-315
	public void appendShouldAddValueCorrectly() {

		clusterConnection.append(KEY_1_BYTES, VALUE_1_BYTES);
		clusterConnection.append(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1.concat(VALUE_2)));
	}

	@Test // DATAREDIS-315
	public void getRangeShouldReturnValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.getRange(KEY_1_BYTES, 0, 2), is(LettuceConverters.toBytes("val")));
	}

	@Test // DATAREDIS-315
	public void setRangeShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.setRange(KEY_1_BYTES, LettuceConverters.toBytes("UE"), 3);

		assertThat(nativeConnection.get(KEY_1), is("valUE1"));
	}

	@Test // DATAREDIS-315
	public void getBitShouldWorkCorrectly() {

		nativeConnection.setbit(KEY_1, 0, 1);
		nativeConnection.setbit(KEY_1, 1, 0);

		assertThat(clusterConnection.getBit(KEY_1_BYTES, 0), is(true));
		assertThat(clusterConnection.getBit(KEY_1_BYTES, 1), is(false));
	}

	@Test // DATAREDIS-315
	public void setBitShouldWorkCorrectly() {

		clusterConnection.setBit(KEY_1_BYTES, 0, true);
		clusterConnection.setBit(KEY_1_BYTES, 1, false);

		assertThat(nativeConnection.getbit(KEY_1, 0), is(1L));
		assertThat(nativeConnection.getbit(KEY_1, 1), is(0L));
	}

	@Test // DATAREDIS-315
	public void bitCountShouldWorkCorrectly() {

		nativeConnection.setbit(KEY_1, 0, 1);
		nativeConnection.setbit(KEY_1, 1, 0);

		assertThat(clusterConnection.bitCount(KEY_1_BYTES), is(1L));
	}

	@Test // DATAREDIS-315
	public void bitCountWithRangeShouldWorkCorrectly() {

		nativeConnection.setbit(KEY_1, 0, 1);
		nativeConnection.setbit(KEY_1, 1, 0);
		nativeConnection.setbit(KEY_1, 2, 1);
		nativeConnection.setbit(KEY_1, 3, 0);
		nativeConnection.setbit(KEY_1, 4, 1);

		assertThat(clusterConnection.bitCount(KEY_1_BYTES, 0, 3), is(3L));
	}

	@Test // DATAREDIS-315
	public void bitOpShouldWorkCorrectly() {

		nativeConnection.set(SAME_SLOT_KEY_1, "foo");
		nativeConnection.set(SAME_SLOT_KEY_2, "bar");

		clusterConnection.bitOp(BitOperation.AND, SAME_SLOT_KEY_3_BYTES, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.get(SAME_SLOT_KEY_3), is("bab"));
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void bitOpShouldThrowExceptionWhenKeysDoNotMapToSameSlot() {
		clusterConnection.bitOp(BitOperation.AND, KEY_1_BYTES, KEY_2_BYTES, KEY_3_BYTES);
	}

	@Test // DATAREDIS-315
	public void strLenShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.strLen(KEY_1_BYTES), is(6L));
	}

	@Test // DATAREDIS-315
	public void rPushShoultAddValuesCorrectly() {

		clusterConnection.rPush(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_2));
	}

	@Test // DATAREDIS-315
	public void lPushShoultAddValuesCorrectly() {

		clusterConnection.lPush(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_2));
	}

	@Test // DATAREDIS-315
	public void rPushNXShoultNotAddValuesWhenKeyDoesNotExist() {

		clusterConnection.rPushX(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.exists(KEY_1), is(false));
	}

	@Test // DATAREDIS-315
	public void lPushNXShoultNotAddValuesWhenKeyDoesNotExist() {

		clusterConnection.lPushX(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.exists(KEY_1), is(false));
	}

	@Test // DATAREDIS-315
	public void lLenShouldCountValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.lLen(KEY_1_BYTES), is(2L));
	}

	@Test // DATAREDIS-315
	public void lRangeShouldGetValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.lRange(KEY_1_BYTES, 0L, -1L), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void lTrimShouldTrimListCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lTrim(KEY_1_BYTES, 2, 3);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_2));
	}

	@Test // DATAREDIS-315
	public void lIndexShouldGetElementAtIndexCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		assertThat(clusterConnection.lIndex(KEY_1_BYTES, 1), is(VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void lInsertShouldAddElementAtPositionCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lInsert(KEY_1_BYTES, Position.AFTER, VALUE_2_BYTES, LettuceConverters.toBytes("booh!"));

		assertThat(nativeConnection.lrange(KEY_1, 0, -1).get(2), is("booh!"));
	}

	@Test // DATAREDIS-315
	public void lSetShouldSetElementAtPositionCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lSet(KEY_1_BYTES, 1L, VALUE_1_BYTES);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1).get(1), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void lRemShouldRemoveElementAtPositionCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lRem(KEY_1_BYTES, 1L, VALUE_1_BYTES);

		assertThat(nativeConnection.llen(KEY_1), is(3L));
	}

	@Test // DATAREDIS-315
	public void lPopShouldReturnElementCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.lPop(KEY_1_BYTES), is(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void rPopShouldReturnElementCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.rPop(KEY_1_BYTES), is(VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void blPopShouldPopElementCorectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.lpush(KEY_2, VALUE_3);

		assertThat(clusterConnection.bLPop(100, KEY_1_BYTES, KEY_2_BYTES).size(), is(2));
	}

	@Test // DATAREDIS-315
	public void blPopShouldPopElementCorectlyWhenKeyOnSameSlot() {

		nativeConnection.lpush(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.lpush(SAME_SLOT_KEY_2, VALUE_3);

		assertThat(clusterConnection.bLPop(100, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES).size(), is(2));
	}

	@Test // DATAREDIS-315
	public void brPopShouldPopElementCorectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.lpush(KEY_2, VALUE_3);

		assertThat(clusterConnection.bRPop(100, KEY_1_BYTES, KEY_2_BYTES).size(), is(2));
	}

	@Test // DATAREDIS-315
	public void brPopShouldPopElementCorectlyWhenKeyOnSameSlot() {

		nativeConnection.lpush(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.lpush(SAME_SLOT_KEY_2, VALUE_3);

		assertThat(clusterConnection.bRPop(100, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES).size(), is(2));
	}

	@Test // DATAREDIS-315
	public void rPopLPushShouldWorkWhenDoNotMapToSameSlot() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.lpush(KEY_2, VALUE_3);

		assertThat(clusterConnection.bLPop(100, KEY_1_BYTES, KEY_2_BYTES).size(), is(2));
	}

	@Test // DATAREDIS-315
	public void bRPopLPushShouldWork() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.bRPopLPush(0, KEY_1_BYTES, KEY_2_BYTES), is(VALUE_1_BYTES));
		assertThat(nativeConnection.exists(KEY_2), is(true));
	}

	@Test // DATAREDIS-315
	public void bRPopLPushShouldWorkOnSameSlotKeys() {

		nativeConnection.lpush(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.bRPopLPush(0, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES), is(VALUE_1_BYTES));
		assertThat(nativeConnection.exists(SAME_SLOT_KEY_2), is(true));
	}

	@Test // DATAREDIS-315
	public void rPopLPushShouldWorkWhenKeysOnSameSlot() {

		nativeConnection.lpush(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.rPopLPush(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES), is(VALUE_1_BYTES));
		assertThat(nativeConnection.exists(SAME_SLOT_KEY_2), is(true));
	}

	@Test // DATAREDIS-315
	public void sAddShouldAddValueToSetCorrectly() {

		clusterConnection.sAdd(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_1), hasItems(VALUE_1, VALUE_2));
	}

	@Test // DATAREDIS-315
	public void sRemShouldRemoveValueFromSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		clusterConnection.sRem(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_1), hasItems(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void sPopShouldPopValueFromSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sPop(KEY_1_BYTES), notNullValue());
	}

	@Test // DATAREDIS-315
	public void sMoveShouldWorkWhenKeysMapToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_3);

		clusterConnection.sMove(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.sismember(SAME_SLOT_KEY_1, VALUE_2), is(false));
		assertThat(nativeConnection.sismember(SAME_SLOT_KEY_2, VALUE_2), is(true));
	}

	@Test // DATAREDIS-315
	public void sMoveShouldWorkWhenKeysDoNotMapToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_3);

		clusterConnection.sMove(KEY_1_BYTES, KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.sismember(KEY_1, VALUE_2), is(false));
		assertThat(nativeConnection.sismember(KEY_2, VALUE_2), is(true));
	}

	@Test // DATAREDIS-315
	public void sCardShouldCountValuesInSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sCard(KEY_1_BYTES), is(2L));
	}

	@Test // DATAREDIS-315
	public void sIsMemberShouldReturnTrueIfValueIsMemberOfSet() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sIsMember(KEY_1_BYTES, VALUE_1_BYTES), is(true));
	}

	@Test // DATAREDIS-315
	public void sIsMemberShouldReturnFalseIfValueIsMemberOfSet() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sIsMember(KEY_1_BYTES, LettuceConverters.toBytes("foo")), is(false));
	}

	@Test // DATAREDIS-315
	public void sInterShouldWorkForKeysMappingToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		assertThat(clusterConnection.sInter(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES), hasItem(VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void sInterShouldWorkForKeysNotMappingToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_2, VALUE_3);

		assertThat(clusterConnection.sInter(KEY_1_BYTES, KEY_2_BYTES), hasItem(VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void sInterStoreShouldWorkForKeysMappingToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		clusterConnection.sInterStore(SAME_SLOT_KEY_3_BYTES, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.smembers(SAME_SLOT_KEY_3), hasItem(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void sInterStoreShouldWorkForKeysNotMappingToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_2, VALUE_3);

		clusterConnection.sInterStore(KEY_3_BYTES, KEY_1_BYTES, KEY_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_3), hasItem(VALUE_2));
	}

	@Test // DATAREDIS-315
	public void sUnionShouldWorkForKeysMappingToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		assertThat(clusterConnection.sUnion(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES),
				hasItems(VALUE_1_BYTES, VALUE_2_BYTES, VALUE_3_BYTES));
	}

	@Test // DATAREDIS-315
	public void sUnionShouldWorkForKeysNotMappingToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_2, VALUE_3);

		assertThat(clusterConnection.sUnion(KEY_1_BYTES, KEY_2_BYTES),
				hasItems(VALUE_1_BYTES, VALUE_2_BYTES, VALUE_3_BYTES));
	}

	@Test // DATAREDIS-315
	public void sUnionStoreShouldWorkForKeysMappingToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		clusterConnection.sUnionStore(SAME_SLOT_KEY_3_BYTES, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.smembers(SAME_SLOT_KEY_3), hasItems(VALUE_1, VALUE_2, VALUE_3));
	}

	@Test // DATAREDIS-315
	public void sUnionStoreShouldWorkForKeysNotMappingToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_2, VALUE_3);

		clusterConnection.sUnionStore(KEY_3_BYTES, KEY_1_BYTES, KEY_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_3), hasItems(VALUE_1, VALUE_2, VALUE_3));
	}

	@Test // DATAREDIS-315
	public void sDiffShouldWorkWhenKeysMapToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		assertThat(clusterConnection.sDiff(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES), hasItems(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315, DATAREDIS-647
	public void sDiffShouldWorkWhenKeysNotMapToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_2, VALUE_3);
		nativeConnection.sadd(KEY_3, VALUE_1, VALUE_3);

		assertThat(clusterConnection.sDiff(KEY_1_BYTES, KEY_2_BYTES), hasItems(VALUE_1_BYTES));
		assertThat(clusterConnection.sDiff(KEY_1_BYTES, KEY_2_BYTES, KEY_3_BYTES), is(empty()));
	}

	@Test // DATAREDIS-315
	public void sDiffStoreShouldWorkWhenKeysMapToSameSlot() {

		nativeConnection.sadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		clusterConnection.sDiffStore(SAME_SLOT_KEY_3_BYTES, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.smembers(SAME_SLOT_KEY_3), hasItems(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void sDiffStoreShouldWorkWhenKeysNotMapToSameSlot() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);
		nativeConnection.sadd(KEY_2, VALUE_2, VALUE_3);

		clusterConnection.sDiffStore(KEY_3_BYTES, KEY_1_BYTES, KEY_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_3), hasItems(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void sMembersShouldReturnValuesContainedInSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sMembers(KEY_1_BYTES), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void sRandMamberShouldReturnValueCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sRandMember(KEY_1_BYTES), notNullValue());
	}

	@Test // DATAREDIS-315
	public void sRandMamberWithCountShouldReturnValueCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sRandMember(KEY_1_BYTES, 3), notNullValue());
	}

	@Test // DATAREDIS-315
	public void sscanShouldRetrieveAllValuesInSetCorrectly() {

		for (int i = 0; i < 30; i++) {
			nativeConnection.sadd(KEY_1, Integer.valueOf(i).toString());
		}

		int count = 0;
		Cursor<byte[]> cursor = clusterConnection.sScan(KEY_1_BYTES, ScanOptions.NONE);
		while (cursor.hasNext()) {
			count++;
			cursor.next();
		}

		assertThat(count, is(30));
	}

	@Test // DATAREDIS-315
	public void zAddShouldAddValueWithScoreCorrectly() {

		clusterConnection.zAdd(KEY_1_BYTES, 10D, VALUE_1_BYTES);
		clusterConnection.zAdd(KEY_1_BYTES, 20D, VALUE_2_BYTES);

		assertThat(nativeConnection.zcard(KEY_1), is(2L));
	}

	@Test // DATAREDIS-674
	public void zAddShouldAddMultipleValuesWithScoreCorrectly() {

		Set<Tuple> tuples = new HashSet<Tuple>();
		tuples.add(new DefaultTuple(VALUE_1_BYTES, 10D));
		tuples.add(new DefaultTuple(VALUE_2_BYTES, 20D));

		clusterConnection.zAdd(KEY_1_BYTES, tuples);

		assertThat(nativeConnection.zcard(KEY_1), is(2L));
	}

	@Test // DATAREDIS-315
	public void zRemShouldRemoveValueWithScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		clusterConnection.zRem(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.zcard(KEY_1), is(1L));
	}

	@Test // DATAREDIS-315
	public void zIncrByShouldIncScoreForValueCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		clusterConnection.zIncrBy(KEY_1_BYTES, 100D, VALUE_1_BYTES);

		assertThat(nativeConnection.zrank(KEY_1, VALUE_1), is(1L));
	}

	@Test // DATAREDIS-315
	public void zRankShouldReturnPositionForValueCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		assertThat(clusterConnection.zRank(KEY_1_BYTES, VALUE_2_BYTES), is(1L));
	}

	@Test // DATAREDIS-315
	public void zRankShouldReturnReversePositionForValueCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		assertThat(clusterConnection.zRevRank(KEY_1_BYTES, VALUE_2_BYTES), is(0L));
	}

	@Test // DATAREDIS-315
	public void zRangeShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRange(KEY_1_BYTES, 1, 2), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void zRangeWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeWithScores(KEY_1_BYTES, 1, 2),
				hasItems((Tuple) new DefaultTuple(VALUE_1_BYTES, 10D), (Tuple) new DefaultTuple(VALUE_2_BYTES, 20D)));
	}

	@Test // DATAREDIS-315
	public void zRangeByScoreShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScore(KEY_1_BYTES, 10, 20), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void zRangeByScoreWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScoreWithScores(KEY_1_BYTES, 10, 20),
				hasItems((Tuple) new DefaultTuple(VALUE_1_BYTES, 10D), (Tuple) new DefaultTuple(VALUE_2_BYTES, 20D)));
	}

	@Test // DATAREDIS-315
	public void zRangeByScoreShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScore(KEY_1_BYTES, 10D, 20D, 0L, 1L), hasItems(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void zRangeByScoreWithScoresShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScoreWithScores(KEY_1_BYTES, 10D, 20D, 0L, 1L),
				hasItems((Tuple) new DefaultTuple(VALUE_1_BYTES, 10D)));
	}

	@Test // DATAREDIS-315
	public void zRevRangeShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRange(KEY_1_BYTES, 1, 2), hasItems(VALUE_3_BYTES, VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void zRevRangeWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeWithScores(KEY_1_BYTES, 1, 2),
				hasItems((Tuple) new DefaultTuple(VALUE_3_BYTES, 5D), (Tuple) new DefaultTuple(VALUE_1_BYTES, 10D)));
	}

	@Test // DATAREDIS-315
	public void zRevRangeByScoreShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScore(KEY_1_BYTES, 10D, 20D), hasItems(VALUE_2_BYTES, VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void zRevRangeByScoreWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScoreWithScores(KEY_1_BYTES, 10D, 20D),
				hasItems((Tuple) new DefaultTuple(VALUE_2_BYTES, 20D), (Tuple) new DefaultTuple(VALUE_1_BYTES, 10D)));
	}

	@Test // DATAREDIS-315
	public void zRevRangeByScoreShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScore(KEY_1_BYTES, 10D, 20D, 0L, 1L), hasItems(VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void zRevRangeByScoreWithScoresShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScoreWithScores(KEY_1_BYTES, 10D, 20D, 0L, 1L),
				hasItems((Tuple) new DefaultTuple(VALUE_2_BYTES, 20D)));
	}

	@Test // DATAREDIS-315
	public void zCountShouldCountValuesInRange() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zCount(KEY_1_BYTES, 10, 20), is(2L));
	}

	@Test // DATAREDIS-315
	public void zCardShouldReturnTotalNumberOfValues() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zCard(KEY_1_BYTES), is(3L));
	}

	@Test // DATAREDIS-315
	public void zScoreShouldRetrieveScoreForValue() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		assertThat(clusterConnection.zScore(KEY_1_BYTES, VALUE_2_BYTES), is(20D));
	}

	@Test // DATAREDIS-315
	public void zRemRangeShouldRemoveValues() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 30D, VALUE_3);

		clusterConnection.zRemRange(KEY_1_BYTES, 1, 2);

		assertThat(nativeConnection.zcard(KEY_1), is(1L));
		assertThat(nativeConnection.zrange(KEY_1, 0, -1), hasItem(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void zRemRangeByScoreShouldRemoveValues() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 30D, VALUE_3);

		clusterConnection.zRemRangeByScore(KEY_1_BYTES, 15D, 25D);

		assertThat(nativeConnection.zcard(KEY_1), is(2L));
		assertThat(nativeConnection.zrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_3));
	}

	@Test // DATAREDIS-315
	public void zUnionStoreShouldWorkForSameSlotKeys() {

		nativeConnection.zadd(SAME_SLOT_KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(SAME_SLOT_KEY_1, 30D, VALUE_3);
		nativeConnection.zadd(SAME_SLOT_KEY_2, 20D, VALUE_2);

		clusterConnection.zUnionStore(SAME_SLOT_KEY_3_BYTES, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.zrange(SAME_SLOT_KEY_3, 0, -1), hasItems(VALUE_1, VALUE_2, VALUE_3));
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void zUnionStoreShouldThrowExceptionWhenKeysDoNotMapToSameSlots() {
		clusterConnection.zUnionStore(KEY_3_BYTES, KEY_1_BYTES, KEY_2_BYTES);
	}

	@Test // DATAREDIS-315
	public void zInterStoreShouldWorkForSameSlotKeys() {

		nativeConnection.zadd(SAME_SLOT_KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(SAME_SLOT_KEY_1, 20D, VALUE_2);

		nativeConnection.zadd(SAME_SLOT_KEY_2, 20D, VALUE_2);
		nativeConnection.zadd(SAME_SLOT_KEY_2, 30D, VALUE_3);

		clusterConnection.zInterStore(SAME_SLOT_KEY_3_BYTES, SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES);

		assertThat(nativeConnection.zrange(SAME_SLOT_KEY_3, 0, -1), hasItems(VALUE_2));
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void zInterStoreShouldThrowExceptionWhenKeysDoNotMapToSameSlots() {
		clusterConnection.zInterStore(KEY_3_BYTES, KEY_1_BYTES, KEY_2_BYTES);
	}

	@Test
	public void zScanShouldReadEntireValueRange() {

		int nrOfValues = 321;
		for (int i = 0; i < nrOfValues; i++) {
			nativeConnection.zadd(KEY_1, i, "value-" + i);
		}

		Cursor<Tuple> tuples = clusterConnection.zScan(KEY_1_BYTES, ScanOptions.NONE);

		int count = 0;
		while (tuples.hasNext()) {

			tuples.next();
			count++;
		}

		assertThat(count, equalTo(nrOfValues));
	}

	@Test // DATAREDIS-315
	public void hSetShouldSetValueCorrectly() {

		clusterConnection.hSet(KEY_1_BYTES, KEY_2_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.hget(KEY_1, KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void hSetNXShouldSetValueCorrectly() {

		clusterConnection.hSetNX(KEY_1_BYTES, KEY_2_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.hget(KEY_1, KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void hSetNXShouldNotSetValueWhenAlreadyExists() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);

		clusterConnection.hSetNX(KEY_1_BYTES, KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.hget(KEY_1, KEY_2), is(VALUE_1));
	}

	@Test // DATAREDIS-315
	public void hGetShouldRetrieveValueCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);

		assertThat(clusterConnection.hGet(KEY_1_BYTES, KEY_2_BYTES), is(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void hMGetShouldRetrieveValueCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hMGet(KEY_1_BYTES, KEY_2_BYTES, KEY_3_BYTES), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void hMSetShouldAddValuesCorrectly() {

		Map<byte[], byte[]> hashes = new HashMap<byte[], byte[]>();
		hashes.put(KEY_2_BYTES, VALUE_1_BYTES);
		hashes.put(KEY_3_BYTES, VALUE_2_BYTES);

		clusterConnection.hMSet(KEY_1_BYTES, hashes);

		assertThat(nativeConnection.hmget(KEY_1, KEY_2, KEY_3), hasItems(VALUE_1, VALUE_2));
	}

	@Test // DATAREDIS-315
	public void hIncrByShouldIncreaseFieldCorretly() {

		nativeConnection.hset(KEY_1, KEY_2, "1");
		nativeConnection.hset(KEY_1, KEY_3, "2");

		clusterConnection.hIncrBy(KEY_1_BYTES, KEY_3_BYTES, 3);

		assertThat(nativeConnection.hget(KEY_1, KEY_3), is("5"));
	}

	@Test // DATAREDIS-315
	public void hIncrByFloatShouldIncreaseFieldCorretly() {

		nativeConnection.hset(KEY_1, KEY_2, "1");
		nativeConnection.hset(KEY_1, KEY_3, "2");

		clusterConnection.hIncrBy(KEY_1_BYTES, KEY_3_BYTES, 3.5D);

		assertThat(nativeConnection.hget(KEY_1, KEY_3), is("5.5"));
	}

	@Test // DATAREDIS-315
	public void hExistsShouldReturnPresenceOfFieldCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);

		assertThat(clusterConnection.hExists(KEY_1_BYTES, KEY_2_BYTES), is(true));
		assertThat(clusterConnection.hExists(KEY_1_BYTES, KEY_3_BYTES), is(false));
		assertThat(clusterConnection.hExists(LettuceConverters.toBytes("foo"), KEY_2_BYTES), is(false));
	}

	@Test // DATAREDIS-315
	public void hDelShouldRemoveFieldsCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		clusterConnection.hDel(KEY_1_BYTES, KEY_2_BYTES);

		assertThat(nativeConnection.hexists(KEY_1, KEY_2), is(false));
		assertThat(nativeConnection.hexists(KEY_1, KEY_3), is(true));
	}

	@Test // DATAREDIS-315
	public void hLenShouldRetrieveSizeCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hLen(KEY_1_BYTES), is(2L));
	}

	@Test // DATAREDIS-315
	public void hKeysShouldRetrieveKeysCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hKeys(KEY_1_BYTES), hasItems(KEY_2_BYTES, KEY_3_BYTES));
	}

	@Test // DATAREDIS-315
	public void hValsShouldRetrieveValuesCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hVals(KEY_1_BYTES), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	@Test // DATAREDIS-315
	public void hGetAllShouldRetrieveEntriesCorrectly() {

		Map<String, String> hashes = new HashMap<String, String>();
		hashes.put(KEY_2, VALUE_1);
		hashes.put(KEY_3, VALUE_2);

		nativeConnection.hmset(KEY_1, hashes);

		Map<byte[], byte[]> hGetAll = clusterConnection.hGetAll(KEY_1_BYTES);

		assertThat(hGetAll.keySet(), hasItems(KEY_2_BYTES, KEY_3_BYTES));
	}

	@Test
	public void hScanShouldReadEntireValueRange() {

		int nrOfValues = 321;
		for (int i = 0; i < nrOfValues; i++) {
			nativeConnection.hset(KEY_1, "key" + i, "value-" + i);
		}

		Cursor<Map.Entry<byte[], byte[]>> cursor = clusterConnection.hScan(KEY_1_BYTES,
				scanOptions().match("key*").build());

		int i = 0;
		while (cursor.hasNext()) {

			cursor.next();
			i++;
		}

		assertThat(i, is(nrOfValues));
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void multiShouldThrowException() {
		clusterConnection.multi();
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void execShouldThrowException() {
		clusterConnection.exec();
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void discardShouldThrowException() {
		clusterConnection.discard();
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void watchShouldThrowException() {
		clusterConnection.watch();
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void unwatchShouldThrowException() {
		clusterConnection.unwatch();
	}

	@Test // DATAREDIS-315
	public void selectShouldAllowSelectionOfDBIndexZero() {
		clusterConnection.select(0);
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void selectShouldThrowExceptionWhenSelectingNonZeroDbIndex() {
		clusterConnection.select(1);
	}

	@Test // DATAREDIS-315
	public void echoShouldReturnInputCorrectly() {
		assertThat(clusterConnection.echo(VALUE_1_BYTES), is(VALUE_1_BYTES));
	}

	@Test // DATAREDIS-315
	public void pingShouldRetrunPongForExistingNode() {
		assertThat(clusterConnection.ping(new RedisClusterNode("127.0.0.1", 7379, null)), is("PONG"));
	}

	@Test // DATAREDIS-315
	public void pingShouldRetrunPong() {
		assertThat(clusterConnection.ping(), is("PONG"));
	}

	@Test(expected = IllegalArgumentException.class) // DATAREDIS-315
	public void pingShouldThrowExceptionWhenNodeNotKnownToCluster() {
		clusterConnection.ping(new RedisClusterNode("127.0.0.1", 1234, null));
	}

	@Test // DATAREDIS-315
	public void flushDbShouldFlushAllClusterNodes() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		clusterConnection.flushDb();

		assertThat(nativeConnection.get(KEY_1), nullValue());
		assertThat(nativeConnection.get(KEY_2), nullValue());
	}

	@Test // DATAREDIS-315
	public void flushDbOnSingleNodeShouldFlushOnlyGivenNodesDb() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		clusterConnection.flushDb(new RedisClusterNode("127.0.0.1", 7379, null));

		assertThat(nativeConnection.get(KEY_1), notNullValue());
		assertThat(nativeConnection.get(KEY_2), nullValue());
	}

	@Test // DATAREDIS-315
	public void zRangeByLexShouldReturnResultCorrectly() {

		nativeConnection.zadd(KEY_1, 0, "a");
		nativeConnection.zadd(KEY_1, 0, "b");
		nativeConnection.zadd(KEY_1, 0, "c");
		nativeConnection.zadd(KEY_1, 0, "d");
		nativeConnection.zadd(KEY_1, 0, "e");
		nativeConnection.zadd(KEY_1, 0, "f");
		nativeConnection.zadd(KEY_1, 0, "g");

		Set<byte[]> values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().lte("c"));

		assertThat(values,
				hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("b"), LettuceConverters.toBytes("c")));
		assertThat(values, not(hasItems(LettuceConverters.toBytes("d"), LettuceConverters.toBytes("e"),
				LettuceConverters.toBytes("f"), LettuceConverters.toBytes("g"))));

		values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().lt("c"));
		assertThat(values, hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("b")));
		assertThat(values, not(hasItem(LettuceConverters.toBytes("c"))));

		values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().gte("aaa").lt("g"));
		assertThat(values, hasItems(LettuceConverters.toBytes("b"), LettuceConverters.toBytes("c"),
				LettuceConverters.toBytes("d"), LettuceConverters.toBytes("e"), LettuceConverters.toBytes("f")));
		assertThat(values, not(hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("g"))));

		values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().gte("e"));
		assertThat(values,
				hasItems(LettuceConverters.toBytes("e"), LettuceConverters.toBytes("f"), LettuceConverters.toBytes("g")));
		assertThat(values, not(hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("b"),
				LettuceConverters.toBytes("c"), LettuceConverters.toBytes("d"))));
	}

	@Test // DATAREDIS-315
	public void infoShouldCollectionInfoFromAllClusterNodes() {
		assertThat(Double.valueOf(clusterConnection.info().size()), closeTo(245d, 35d));
	}

	@Test // DATAREDIS-315
	public void clientListShouldGetInfosForAllClients() {
		assertThat(clusterConnection.getClientList().isEmpty(), is(false));
	}

	@Test // DATAREDIS-315
	public void getClusterNodeForKeyShouldReturnNodeCorrectly() {
		assertThat((RedisNode) clusterConnection.clusterGetNodeForKey(KEY_1_BYTES), is(new RedisNode("127.0.0.1", 7380)));
	}

	@Test // DATAREDIS-315
	public void countKeysShouldReturnNumberOfKeysInSlot() {

		nativeConnection.set(SAME_SLOT_KEY_1, VALUE_1);
		nativeConnection.set(SAME_SLOT_KEY_2, VALUE_2);

		assertThat(clusterConnection.clusterCountKeysInSlot(ClusterSlotHashUtil.calculateSlot(SAME_SLOT_KEY_1)), is(2L));
	}

	@Test // DATAREDIS-315
	public void pfAddShouldAddValuesCorrectly() {

		clusterConnection.pfAdd(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES, VALUE_3_BYTES);

		assertThat(((RedisHLLCommands<String, String>) nativeConnection).pfcount(KEY_1), is(3L));
	}

	@Test // DATAREDIS-315
	public void pfCountShouldAllowCountingOnSingleKey() {

		((RedisHLLCommands<String, String>) nativeConnection).pfadd(KEY_1, VALUE_1, VALUE_2, VALUE_3);

		assertThat(clusterConnection.pfCount(KEY_1_BYTES), is(3L));
	}

	@Test // DATAREDIS-315
	public void pfCountShouldAllowCountingOnSameSlotKeys() {

		((RedisHLLCommands<String, String>) nativeConnection).pfadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		((RedisHLLCommands<String, String>) nativeConnection).pfadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		assertThat(clusterConnection.pfCount(SAME_SLOT_KEY_1_BYTES, SAME_SLOT_KEY_2_BYTES), is(3L));
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void pfCountShouldThrowErrorCountingOnDifferentSlotKeys() {

		((RedisHLLCommands<String, String>) nativeConnection).pfadd(KEY_1, VALUE_1, VALUE_2);
		((RedisHLLCommands<String, String>) nativeConnection).pfadd(KEY_2, VALUE_2, VALUE_3);

		clusterConnection.pfCount(KEY_1_BYTES, KEY_2_BYTES);
	}

	@Test // DATAREDIS-315
	public void pfMergeShouldWorkWhenAllKeysMapToSameSlot() {

		((RedisHLLCommands<String, String>) nativeConnection).pfadd(SAME_SLOT_KEY_1, VALUE_1, VALUE_2);
		((RedisHLLCommands<String, String>) nativeConnection).pfadd(SAME_SLOT_KEY_2, VALUE_2, VALUE_3);

		((RedisHLLCommands<String, String>) nativeConnection).pfmerge(SAME_SLOT_KEY_3, SAME_SLOT_KEY_1, SAME_SLOT_KEY_2);

		assertThat(((RedisHLLCommands<String, String>) nativeConnection).pfcount(SAME_SLOT_KEY_3), is(3L));
	}

	@Test(expected = DataAccessException.class) // DATAREDIS-315
	public void pfMergeShouldThrowErrorOnDifferentSlotKeys() {
		clusterConnection.pfMerge(KEY_3_BYTES, KEY_1_BYTES, KEY_2_BYTES);
	}

	@Test // DATAREDIS-315
	public void infoShouldCollectInfoForSpecificNode() {

		Properties properties = clusterConnection.info(new RedisClusterNode(CLUSTER_HOST, MASTER_NODE_2_PORT));

		assertThat(properties.getProperty("tcp_port"), is(Integer.toString(MASTER_NODE_2_PORT)));
	}

	@Test // DATAREDIS-315
	public void infoShouldCollectInfoForSpecificNodeAndSection() {

		Properties properties = clusterConnection.info(new RedisClusterNode(CLUSTER_HOST, MASTER_NODE_2_PORT), "server");

		assertThat(properties.getProperty("tcp_port"), is(Integer.toString(MASTER_NODE_2_PORT)));
		assertThat(properties.getProperty("used_memory"), nullValue());
	}

	@Test // DATAREDIS-315
	public void getConfigShouldLoadCumulatedConfiguration() {

		List<String> result = clusterConnection.getConfig("*max-*-entries*");

		// config get *max-*-entries on redis 3.0.7 returns 8 entries per node while on 3.2.0-rc3 returns 6.
		// @link https://github.com/spring-projects/spring-data-redis/pull/187
		assertThat(result.size() % 6, is(0));
		for (int i = 0; i < result.size(); i++) {

			if (i % 2 == 0) {
				assertThat(result.get(i), startsWith(CLUSTER_HOST));
			} else {
				assertThat(result.get(i), not(startsWith(CLUSTER_HOST)));
			}
		}
	}

	@Test // DATAREDIS-315
	public void getConfigShouldLoadConfigurationOfSpecificNode() {

		List<String> result = clusterConnection.getConfig(new RedisClusterNode(CLUSTER_HOST, SLAVEOF_NODE_1_PORT), "*");

		ListIterator<String> it = result.listIterator();
		Integer valueIndex = null;
		while (it.hasNext()) {

			String cur = it.next();
			if (cur.equals("slaveof")) {
				valueIndex = it.nextIndex();
				break;
			}
		}

		assertThat(valueIndex, notNullValue());
		assertThat(result.get(valueIndex), endsWith("7379"));
	}

	@Test // DATAREDIS-315
	public void clusterGetSlavesShouldReturnSlaveCorrectly() {

		Set<RedisClusterNode> slaves = clusterConnection
				.clusterGetSlaves(new RedisClusterNode(CLUSTER_HOST, MASTER_NODE_1_PORT));

		assertThat(slaves.size(), is(1));
		assertThat(slaves, hasItem(new RedisClusterNode(CLUSTER_HOST, SLAVEOF_NODE_1_PORT)));
	}

	@Test // DATAREDIS-315
	public void clusterGetMasterSlaveMapShouldListMastersAndSlavesCorrectly() {

		Map<RedisClusterNode, Collection<RedisClusterNode>> masterSlaveMap = clusterConnection.clusterGetMasterSlaveMap();

		assertThat(masterSlaveMap, notNullValue());
		assertThat(masterSlaveMap.size(), is(3));
		assertThat(masterSlaveMap.get(new RedisClusterNode(CLUSTER_HOST, MASTER_NODE_1_PORT)),
				hasItem(new RedisClusterNode(CLUSTER_HOST, SLAVEOF_NODE_1_PORT)));
		assertThat(masterSlaveMap.get(new RedisClusterNode(CLUSTER_HOST, MASTER_NODE_2_PORT)).isEmpty(), is(true));
		assertThat(masterSlaveMap.get(new RedisClusterNode(CLUSTER_HOST, MASTER_NODE_3_PORT)).isEmpty(), is(true));
	}

	@Test // DATAREDIS-316
	public void setWithExpirationInSecondsShouldWorkCorrectly() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES, Expiration.seconds(1), SetOption.upsert());

		assertThat(nativeConnection.exists(KEY_1), is(true));
		assertThat(nativeConnection.ttl(KEY_1), is(1L));
	}

	@Test // DATAREDIS-316
	public void setWithExpirationInMillisecondsShouldWorkCorrectly() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES, Expiration.milliseconds(500), SetOption.upsert());

		assertThat(nativeConnection.exists(KEY_1), is(true));
		assertThat(nativeConnection.pttl(KEY_1).doubleValue(), is(closeTo(500d, 499d)));
	}

	@Test // DATAREDIS-316
	public void setWithOptionIfPresentShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		clusterConnection.set(KEY_1_BYTES, VALUE_2_BYTES, Expiration.persistent(), SetOption.ifPresent());
	}

	@Test // DATAREDIS-316
	public void setWithOptionIfAbsentShouldWorkCorrectly() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES, Expiration.persistent(), SetOption.ifAbsent());

		assertThat(nativeConnection.exists(KEY_1), is(true));
		assertThat(nativeConnection.ttl(KEY_1), is(-1L));
	}

	@Test // DATAREDIS-316
	public void setWithExpirationAndIfAbsentShouldWorkCorrectly() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES, Expiration.seconds(1), SetOption.ifAbsent());

		assertThat(nativeConnection.exists(KEY_1), is(true));
		assertThat(nativeConnection.ttl(KEY_1), is(1L));
	}

	@Test // DATAREDIS-316
	public void setWithExpirationAndIfAbsentShouldNotBeAppliedWhenKeyExists() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.set(KEY_1_BYTES, VALUE_2_BYTES, Expiration.seconds(1), SetOption.ifAbsent());

		assertThat(nativeConnection.exists(KEY_1), is(true));
		assertThat(nativeConnection.ttl(KEY_1), is(-1L));
		assertThat(nativeConnection.get(KEY_1), is(equalTo(VALUE_1)));

	}

	@Test // DATAREDIS-316
	public void setWithExpirationAndIfPresentShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.set(KEY_1_BYTES, VALUE_2_BYTES, Expiration.seconds(1), SetOption.ifPresent());

		assertThat(nativeConnection.exists(KEY_1), is(true));
		assertThat(nativeConnection.ttl(KEY_1), is(1L));
		assertThat(nativeConnection.get(KEY_1), is(equalTo(VALUE_2)));

	}

	@Test // DATAREDIS-316
	public void setWithExpirationAndIfPresentShouldNotBeAppliedWhenKeyDoesNotExists() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES, Expiration.seconds(1), SetOption.ifPresent());

		assertThat(nativeConnection.exists(KEY_1), is(false));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoAddSingleGeoLocation() {
		assertThat(clusterConnection.geoAdd(KEY_1_BYTES, PALERMO_BYTES), is(1L));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoAddMultipleGeoLocations() {
		assertThat(clusterConnection.geoAdd(KEY_1_BYTES,
				Arrays.asList(PALERMO_BYTES, ARIGENTO_BYTES, CATANIA_BYTES, PALERMO_BYTES)), is(3L));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoDist() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		Distance distance = clusterConnection.geoDist(KEY_1_BYTES, PALERMO_BYTES.getName(), CATANIA_BYTES.getName());
		assertThat(distance.getValue(), is(closeTo(166274.15156960033D, 0.005)));
		assertThat(distance.getUnit(), is("m"));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoDistWithMetric() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		Distance distance = clusterConnection.geoDist(KEY_1_BYTES, PALERMO_BYTES.getName(), CATANIA_BYTES.getName(),
				KILOMETERS);
		assertThat(distance.getValue(), is(closeTo(166.27415156960033D, 0.005)));
		assertThat(distance.getUnit(), is("km"));

	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoHash() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		List<String> result = clusterConnection.geoHash(KEY_1_BYTES, PALERMO_BYTES.getName(), CATANIA_BYTES.getName());
		assertThat(result, contains("sqc8b49rny0", "sqdtr74hyu0"));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoHashNonExisting() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		List<String> result = clusterConnection.geoHash(KEY_1_BYTES, PALERMO_BYTES.getName(), ARIGENTO_BYTES.getName(),
				CATANIA_BYTES.getName());
		assertThat(result, contains("sqc8b49rny0", (String) null, "sqdtr74hyu0"));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoPosition() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		List<Point> positions = clusterConnection.geoPos(KEY_1_BYTES, PALERMO_BYTES.getName(), CATANIA_BYTES.getName());

		assertThat(positions.get(0).getX(), is(closeTo(POINT_PALERMO.getX(), 0.005)));
		assertThat(positions.get(0).getY(), is(closeTo(POINT_PALERMO.getY(), 0.005)));

		assertThat(positions.get(1).getX(), is(closeTo(POINT_CATANIA.getX(), 0.005)));
		assertThat(positions.get(1).getY(), is(closeTo(POINT_CATANIA.getY(), 0.005)));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoPositionNonExisting() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		List<Point> positions = clusterConnection.geoPos(KEY_1_BYTES, PALERMO_BYTES.getName(), ARIGENTO_BYTES.getName(),
				CATANIA_BYTES.getName());

		assertThat(positions.get(0).getX(), is(closeTo(POINT_PALERMO.getX(), 0.005)));
		assertThat(positions.get(0).getY(), is(closeTo(POINT_PALERMO.getY(), 0.005)));

		assertThat(positions.get(1), is(nullValue()));

		assertThat(positions.get(2).getX(), is(closeTo(POINT_CATANIA.getX(), 0.005)));
		assertThat(positions.get(2).getY(), is(closeTo(POINT_CATANIA.getY(), 0.005)));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRadiusShouldReturnMembersCorrectly() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		GeoResults<GeoLocation<byte[]>> result = clusterConnection.geoRadius(KEY_1_BYTES,
				new Circle(new Point(15D, 37D), new Distance(150D, KILOMETERS)));
		assertThat(result.getContent(), hasSize(2));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRadiusShouldReturnDistanceCorrectly() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		GeoResults<GeoLocation<byte[]>> result = clusterConnection.geoRadius(KEY_1_BYTES,
				new Circle(new Point(15D, 37D), new Distance(200D, KILOMETERS)), newGeoRadiusArgs().includeDistance());

		assertThat(result.getContent(), hasSize(3));
		assertThat(result.getContent().get(0).getDistance().getValue(), is(closeTo(130.423D, 0.005)));
		assertThat(result.getContent().get(0).getDistance().getUnit(), is("km"));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRadiusShouldApplyLimit() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		GeoResults<GeoLocation<byte[]>> result = clusterConnection.geoRadius(KEY_1_BYTES,
				new Circle(new Point(15D, 37D), new Distance(200D, KILOMETERS)), newGeoRadiusArgs().limit(2));

		assertThat(result.getContent(), hasSize(2));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRadiusByMemberShouldReturnMembersCorrectly() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		GeoResults<GeoLocation<byte[]>> result = clusterConnection.geoRadiusByMember(KEY_1_BYTES, PALERMO_BYTES.getName(),
				new Distance(100, KILOMETERS), newGeoRadiusArgs().sortAscending());

		assertThat(result.getContent().get(0).getContent().getName(), is(PALERMO_BYTES.getName()));
		assertThat(result.getContent().get(1).getContent().getName(), is(ARIGENTO_BYTES.getName()));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRadiusByMemberShouldReturnDistanceCorrectly() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		GeoResults<GeoLocation<byte[]>> result = clusterConnection.geoRadiusByMember(KEY_1_BYTES, PALERMO_BYTES.getName(),
				new Distance(100, KILOMETERS), newGeoRadiusArgs().includeDistance());

		assertThat(result.getContent(), hasSize(2));
		assertThat(result.getContent().get(0).getDistance().getValue(), is(closeTo(90.978D, 0.005)));
		assertThat(result.getContent().get(0).getDistance().getUnit(), is("km"));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRadiusByMemberShouldApplyLimit() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		GeoResults<GeoLocation<byte[]>> result = clusterConnection.geoRadiusByMember(KEY_1_BYTES, PALERMO_BYTES.getName(),
				new Distance(200, KILOMETERS), newGeoRadiusArgs().limit(2));

		assertThat(result.getContent(), hasSize(2));
	}

	@Test // DATAREDIS-438
	@IfProfileValue(name = "redisVersion", value = "3.2+")
	public void geoRemoveDeletesMembers() {

		nativeConnection.geoadd(KEY_1, PALERMO.getPoint().getX(), PALERMO.getPoint().getY(), PALERMO.getName());
		nativeConnection.geoadd(KEY_1, ARIGENTO.getPoint().getX(), ARIGENTO.getPoint().getY(), ARIGENTO.getName());
		nativeConnection.geoadd(KEY_1, CATANIA.getPoint().getX(), CATANIA.getPoint().getY(), CATANIA.getName());

		assertThat(clusterConnection.geoRemove(KEY_1_BYTES, ARIGENTO_BYTES.getName()), is(1L));
	}
}
