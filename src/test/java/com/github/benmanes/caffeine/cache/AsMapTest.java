/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.matchers.IsEmptyMap.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Map;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.ImmutableMap;

/**
 * The test cases for the {@link Cache#asMap()} view and its serializability. These tests do not
 * validate eviction management or concurrency behavior.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsMapTest {

  /* ---------------- is empty / size / clear -------------- */

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void isEmpty(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.isEmpty(), is(context.initiallyEmpty()));
    if (map.isEmpty()) {
      assertThat(map, is(emptyMap()));
    }
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @CacheSpec
  @Test(dataProvider = "maps")
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    map.clear();
    assertThat(map, is(emptyMap()));
    assertThat(map, hasRemovalNotifications(context,
        (int) context.initialSize(), RemovalCause.EXPLICIT));
  }

  /* ---------------- equals / hashCode -------------- */

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_null(Map<Integer, Integer> map) {
    assertThat(map.equals(null), is(false));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_self(Map<Integer, Integer> map) {
    assertThat(map.equals(map), is(true));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.equals(context.original()), is(true));
    assertThat(context.original().equals(map), is(true));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.hashCode(), is(equalTo(context.original().hashCode())));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode_self(Map<Integer, Integer> map) {
    assertThat(map.hashCode(), is(equalTo(map.hashCode())));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(Map<Integer, Integer> map) {
    Map<Integer, Integer> other = ImmutableMap.of(1, -1, 2, -2, 3, -3);
    assertThat(map.equals(other), is(false));
    assertThat(other.equals(map), is(false));
    assertThat(map.hashCode(), is(not(equalTo(other.hashCode()))));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(Map<Integer, Integer> map) {
    Map<Integer, Integer> other = ImmutableMap.of(1, -1, 2, -2, 3, -3);
    assertThat(map.equals(other), is(false));
    assertThat(other.equals(map), is(false));
    assertThat(map.hashCode(), is(not(equalTo(other.hashCode()))));

    Map<Integer, Integer> empty = ImmutableMap.of();
    assertThat(map.equals(empty), is(false));
    assertThat(empty.equals(map), is(false));
    assertThat(map.hashCode(), is(not(equalTo(empty.hashCode()))));
  }

  /* ---------------- contains -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "maps", expectedExceptions = NullPointerException.class)
  public void containsKey_null(Map<Integer, Integer> map) {
    map.containsKey(null);
  }

  @Test(dataProvider = "maps")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.containsKey(key), is(true));
    }
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.containsKey(context.absentKey()), is(false));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "maps", expectedExceptions = NullPointerException.class)
  public void containsValue_null(Map<Integer, Integer> map) {
    map.containsValue(null);
  }

  @Test(dataProvider = "maps")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.containsValue(-key), is(true));
    }
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.containsValue(-context.absentKey()), is(false));
  }

  /* ---------------- get -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "maps", expectedExceptions = NullPointerException.class)
  public void get_null(Map<Integer, Integer> map) {
    map.get(null);
  }

  @Test(dataProvider = "maps")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.get(key), is(-key));
    }
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.get(context.absentKey()), is(nullValue()));
  }

  /* ---------------- put -------------- */

  @Test(dataProvider = "maps", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKey(Map<Integer, Integer> map) {
    map.put(null, 1);
  }

  @Test(dataProvider = "maps", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullValue(Map<Integer, Integer> map) {
    map.put(1, null);
  }

  @Test(dataProvider = "maps", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKeyAndValue(Map<Integer, Integer> map) {
    map.put(null, null);
  }

  @Test(dataProvider = "maps")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_inserted(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.put(context.absentKey(), -context.absentKey()), is(nullValue()));
    assertThat(map.get(context.absentKey()), is(-context.absentKey()));
    assertThat(map.size(), is((int) context.initialSize() + 1));
  }

  @Test(dataProvider = "maps")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replaced_sameValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.put(key, -key), is(-key));
      assertThat(map.get(key), is(-key));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replaced_differentValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.put(key, -context.absentKey()), is(-key));
      assertThat(map.get(key), is(-context.absentKey()));
    }
    assertThat(map.size(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- putAll -------------- */

}