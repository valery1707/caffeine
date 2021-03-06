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

import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * An in-memory cache that has no capabilities for bounding the map. This implementation provides
 * a lightweight wrapper on top of {@link ConcurrentHashMap}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class UnboundedLocalCache<K, V> implements LocalCache<K, V> {
  @Nullable final RemovalListener<K, V> removalListener;
  final ConcurrentHashMap<K, V> data;
  final CacheWriter<K, V> writer;
  final Executor executor;
  final Ticker ticker;

  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  boolean isRecordingStats;
  StatsCounter statsCounter;

  UnboundedLocalCache(Caffeine<? super K, ? super V> builder, boolean async) {
    this.data = new ConcurrentHashMap<K, V>(builder.getInitialCapacity());
    this.statsCounter = builder.getStatsCounterSupplier().get();
    this.removalListener = builder.getRemovalListener(async);
    this.isRecordingStats = builder.isRecordingStats();
    this.writer = builder.getCacheWriter();
    this.executor = builder.getExecutor();
    this.ticker = builder.getTicker();
  }

  /* ---------------- Cache -------------- */

  @Override
  public V getIfPresent(Object key, boolean recordStats) {
    V value = data.get(key);

    if (recordStats) {
      if (value == null) {
        statsCounter.recordMisses(1);
      } else {
        statsCounter.recordHits(1);
      }
    }
    return value;
  }

  @Override
  public long estimatedSize() {
    return data.mappingCount();
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int hits = 0;
    int misses = 0;
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      V value = data.get(key);
      if (value == null) {
        misses++;
      } else {
        hits++;
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        result.put(castKey, value);
      }
    }
    statsCounter.recordHits(hits);
    statsCounter.recordMisses(misses);
    return Collections.unmodifiableMap(result);
  }

  @Override
  public void cleanUp() {}

  @Override
  public StatsCounter statsCounter() {
    return statsCounter;
  }

  void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    requireNonNull(removalListener, "Notification should be guarded with a check");
    executor.execute(() -> removalListener.onRemoval(key, value, cause));
  }

  boolean hasRemovalListener() {
    return (removalListener != null);
  }

  @Override
  public RemovalListener<K, V> removalListener() {
    return removalListener;
  }

  @Override
  public boolean isRecordingStats() {
    return isRecordingStats;
  }

  @Override
  public Executor executor() {
    return executor;
  }

  @Override
  public Ticker expirationTicker() {
    return Ticker.disabledTicker();
  }

  @Override
  public Ticker statsTicker() {
    return ticker;
  }

  /* ---------------- JDK8+ Map extensions -------------- */

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    data.forEach(action);
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    requireNonNull(function);

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"unchecked", "rawtypes"})
    K[] notificationKey = (K[]) new Object[1];
    @SuppressWarnings({"unchecked", "rawtypes"})
    V[] notificationValue = (V[]) new Object[1];
    data.replaceAll((key, value) -> {
      if (notificationKey[0] != null) {
        notifyRemoval(notificationKey[0], notificationValue[0], RemovalCause.REPLACED);
        notificationValue[0] = null;
        notificationKey[0] = null;
      }

      V newValue = requireNonNull(function.apply(key, value));
      if (newValue != value) {
        writer.write(key, newValue);
      }
      if (hasRemovalListener() && (newValue != value)) {
        notificationKey[0] = key;
        notificationValue[0] = value;
      }

      return newValue;
    });
    if (notificationKey[0] != null) {
      notifyRemoval(notificationKey[0], notificationValue[0], RemovalCause.REPLACED);
    }
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean isAsync) {
    requireNonNull(mappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    V value = data.get(key);
    if (value != null) {
      statsCounter.recordHits(1);
      return value;
    }

    boolean[] missed = new boolean[1];
    value = data.computeIfAbsent(key, k -> {
      // Do not communicate to CacheWriter on a load
      missed[0] = true;
      return statsAware(mappingFunction, isAsync).apply(key);
    });
    if (!missed[0]) {
      statsCounter.recordHits(1);
    }
    return value;
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    if (!data.containsKey(key)) {
      return null;
    }

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"unchecked", "rawtypes"})
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];
    V nv = data.computeIfPresent(key, (K k, V value) -> {
      V newValue = statsAware(remappingFunction, false, false).apply(k, value);

      cause[0] = (newValue == null) ? RemovalCause.EXPLICIT : RemovalCause.REPLACED;
      if (hasRemovalListener() && (newValue != value)) {
        oldValue[0] = value;
      }

      return newValue;
    });
    if (oldValue[0] != null) {
      notifyRemoval(key, oldValue[0], cause[0]);
    }
    return nv;
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss, boolean isAsync) {
    requireNonNull(remappingFunction);
    return remap(key, statsAware(remappingFunction, recordMiss, isAsync));
  }

  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);
    requireNonNull(value);

    return remap(key, (k, oldValue) ->
      (oldValue == null) ? value : statsAware(remappingFunction).apply(oldValue, value));
  }

  /**
   * A {@link Map#compute(Object, BiFunction)} that does not directly record any cache statistics.
   *
   * @param key key with which the specified value is to be associated
   * @param remappingFunction the function to compute a value
   * @return the new value associated with the specified key, or null if none
   */
  V remap(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"unchecked", "rawtypes"})
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];
    V nv = data.compute(key, (K k, V value) -> {
      V newValue = remappingFunction.apply(k, value);
      if ((value == null) && (newValue == null)) {
        return null;
      }

      cause[0] = (newValue == null) ? RemovalCause.EXPLICIT : RemovalCause.REPLACED;
      if (hasRemovalListener() && (value != null) && (newValue != value)) {
        oldValue[0] = value;
      }

      return newValue;
    });
    if (oldValue[0] != null) {
      notifyRemoval(key, oldValue[0], cause[0]);
    }
    return nv;
  }

  /* ---------------- Concurrent Map -------------- */

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public void clear() {
    if (!hasRemovalListener() && (writer == CacheWriter.disabledWriter())) {
      data.clear();
      return;
    }
    for (K key : data.keySet()) {
      remove(key);
    }
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public boolean containsKey(Object key) {
    return data.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return data.containsValue(value);
  }

  @Override
  public V get(Object key) {
    return getIfPresent(key, false);
  }

  @Override
  public V put(K key, V value) {
    return put(key, value, true);
  }

  @Override
  public V put(K key, V value, boolean notifyWriter) {
    requireNonNull(value);

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"unchecked", "rawtypes"})
    V oldValue[] = (V[]) new Object[1];
    data.compute(key, (k, v) -> {
      if (notifyWriter && (value != v)) {
        writer.write(key, value);
      }
      oldValue[0] = v;
      return value;
    });

    if (hasRemovalListener() && (oldValue[0] != null) && (oldValue[0] != value)) {
      notifyRemoval(key, oldValue[0], RemovalCause.REPLACED);
    }

    return oldValue[0];
  }

  @Override
  public V putIfAbsent(K key, V value) {
    requireNonNull(value);

    boolean[] wasAbsent = new boolean[1];
    V val = data.computeIfAbsent(key, k -> {
      writer.write(key, value);
      wasAbsent[0] = true;
      return value;
    });
    return wasAbsent[0] ? null : val;
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    if (!hasRemovalListener() && (writer == CacheWriter.disabledWriter())) {
      data.putAll(map);
      return;
    }
    for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public V remove(Object key) {
    @SuppressWarnings("unchecked")
    K castKey = (K) key;
    @SuppressWarnings({"unchecked", "rawtypes"})
    V oldValue[] = (V[]) new Object[1];

    data.computeIfPresent(castKey, (k, v) -> {
      writer.delete(castKey, v, RemovalCause.EXPLICIT);
      oldValue[0] = v;
      return null;
    });

    if (hasRemovalListener() && (oldValue[0] != null)) {
      notifyRemoval(castKey, oldValue[0], RemovalCause.EXPLICIT);
    }

    return oldValue[0];
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (value == null) {
      requireNonNull(key);
      return false;
    }

    @SuppressWarnings("unchecked")
    K castKey = (K) key;
    @SuppressWarnings({"unchecked", "rawtypes"})
    V oldValue[] = (V[]) new Object[1];

    data.computeIfPresent(castKey, (k, v) -> {
      if (v.equals(value)) {
        writer.delete(castKey, v, RemovalCause.EXPLICIT);
        oldValue[0] = v;
        return null;
      }
      return v;
    });

    boolean removed = (oldValue[0] != null);
    if (hasRemovalListener() && removed) {
      notifyRemoval(castKey, oldValue[0], RemovalCause.EXPLICIT);
    }
    return removed;
  }

  @Override
  public V replace(K key, V value) {
    requireNonNull(value);

    @SuppressWarnings({"unchecked", "rawtypes"})
    V oldValue[] = (V[]) new Object[1];
    data.computeIfPresent(key, (k, v) -> {
      if (value != v) {
        writer.write(key, value);
      }
      oldValue[0] = v;
      return value;
    });

    if (hasRemovalListener() && (oldValue[0] != null) && (oldValue[0] != value)) {
      notifyRemoval(key, value, RemovalCause.REPLACED);
    }
    return oldValue[0];
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(oldValue);
    requireNonNull(newValue);

    @SuppressWarnings({"unchecked", "rawtypes"})
    V prev[] = (V[]) new Object[1];
    data.computeIfPresent(key, (k, v) -> {
      if (v.equals(oldValue)) {
        if (newValue != v) {
          writer.write(key, newValue);
        }
        prev[0] = v;
        return newValue;
      }
      return v;
    });

    boolean replaced = (prev[0] != null);
    if (hasRemovalListener() && replaced && (prev[0] != newValue)) {
      notifyRemoval(key, prev[0], RemovalCause.REPLACED);
    }
    return replaced;
  }

  @Override
  public boolean equals(Object o) {
    return data.equals(o);
  }

  @Override
  public int hashCode() {
    return data.hashCode();
  }

  @Override
  public String toString() {
    return data.toString();
  }

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySetView<>(this)) : ks;
  }

  @Override
  public Collection<V> values() {
    final Collection<V> vs = values;
    return (vs == null) ? (values = new ValuesView<>(this)) : vs;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    final Set<Entry<K, V>> es = entrySet;
    return (es == null) ? (entrySet = new EntrySetView<>(this)) : es;
  }

  /** An adapter to safely externalize the keys. */
  static final class KeySetView<K> extends AbstractSet<K> {
    final UnboundedLocalCache<K, ?> local;

    KeySetView(UnboundedLocalCache<K, ?> local) {
      this.local = requireNonNull(local);
    }

    @Override
    public boolean isEmpty() {
      return local.isEmpty();
    }

    @Override
    public int size() {
      return local.size();
    }

    @Override
    public void clear() {
      local.clear();
    }

    @Override
    public boolean contains(Object o) {
      return local.containsKey(o);
    }

    @Override
    public boolean remove(Object obj) {
      return (local.remove(obj) != null);
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<K>(local);
    }

    @Override
    public Spliterator<K> spliterator() {
      return local.data.keySet().spliterator();
    }
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K> implements Iterator<K> {
    final UnboundedLocalCache<K, ?> cache;
    final Iterator<K> iterator;
    K current;

    KeyIterator(UnboundedLocalCache<K, ?> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.data.keySet().iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      current = iterator.next();
      return current;
    }

    @Override
    public void remove() {
      Caffeine.requireState(current != null);
      cache.remove(current);
      current = null;
    }
  }

  /** An adapter to safely externalize the values. */
  static final class ValuesView<K, V> extends AbstractCollection<V> {
    final UnboundedLocalCache<K, V> cache;

    ValuesView(UnboundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public boolean isEmpty() {
      return cache.isEmpty();
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object o) {
      return cache.containsValue(o);
    }

    @Override
    public boolean remove(Object o) {
      requireNonNull(o);
      return super.remove(o);
    }

    @Override
    public Iterator<V> iterator() {
      return new ValuesIterator<>(cache);
    }

    @Override
    public Spliterator<V> spliterator() {
      return cache.data.values().spliterator();
    }
  }

  /** An adapter to safely externalize the value iterator. */
  static final class ValuesIterator<K, V> implements Iterator<V> {
    final UnboundedLocalCache<K, V> cache;
    final Iterator<Entry<K, V>> iterator;
    Entry<K, V> entry;

    ValuesIterator(UnboundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.data.entrySet().iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      entry = iterator.next();
      return entry.getValue();
    }

    @Override
    public void remove() {
      Caffeine.requireState(entry != null);
      cache.remove(entry.getKey());
      entry = null;
    }
  }

  /** An adapter to safely externalize the entries. */
  static final class EntrySetView<K, V> extends AbstractSet<Entry<K, V>> {
    final UnboundedLocalCache<K, V> cache;

    EntrySetView(UnboundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public boolean isEmpty() {
      return cache.isEmpty();
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) o;
      V value = cache.get(entry.getKey());
      return (value != null) && value.equals(entry.getValue());
    }

    @Override
    public boolean remove(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      return cache.remove(entry.getKey(), entry.getValue());
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator<>(cache);
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      return cache.data.entrySet().spliterator();
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
    final UnboundedLocalCache<K, V> cache;
    final Iterator<Entry<K, V>> iterator;
    Entry<K, V> entry;

    EntryIterator(UnboundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.data.entrySet().iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<K, V> next() {
      entry = iterator.next();
      return new WriteThroughEntry<>(cache, entry.getKey(), entry.getValue());
    }

    @Override
    public void remove() {
      Caffeine.requireState(entry != null);
      cache.remove(entry.getKey());
      entry = null;
    }
  }

  /* ---------------- Manual Cache -------------- */

  static class UnboundedLocalManualCache<K, V>
      implements LocalManualCache<UnboundedLocalCache<K, V>, K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final UnboundedLocalCache<K, V> cache;
    Policy<K, V> policy;

    UnboundedLocalManualCache(Caffeine<K, V> builder) {
      cache = new UnboundedLocalCache<>(builder, false);
    }

    @Override
    public UnboundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public Policy<K, V> policy() {
      return (policy == null) ? (policy = new UnboundedPolicy<>(cache.isRecordingStats)) : policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      SerializationProxy<K, V> proxy = new SerializationProxy<>();
      proxy.isRecordingStats = cache.isRecordingStats;
      proxy.removalListener = cache.removalListener;
      proxy.ticker = cache.ticker;
      proxy.writer = cache.writer;
      return proxy;
    }
  }

  /** An eviction policy that supports no boundings. */
  static final class UnboundedPolicy<K, V> implements Policy<K, V> {
    private final boolean isRecordingStats;

    UnboundedPolicy(boolean isRecordingStats) {
      this.isRecordingStats = isRecordingStats;
    }
    @Override public boolean isRecordingStats() {
      return isRecordingStats;
    }
    @Override public Optional<Eviction<K, V>> eviction() {
      return Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> expireAfterAccess() {
      return Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> expireAfterWrite() {
      return Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> refreshAfterWrite() {
      return Optional.empty();
    }
  }

  /* ---------------- Loading Cache -------------- */

  static final class UnboundedLocalLoadingCache<K, V> extends UnboundedLocalManualCache<K, V>
      implements LocalLoadingCache<UnboundedLocalCache<K, V>, K, V> {
    private static final long serialVersionUID = 1;

    final CacheLoader<? super K, V> loader;
    final Function<K, V> mappingFunction;
    final boolean hasBulkLoader;

    UnboundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder);
      this.loader = loader;
      this.hasBulkLoader = hasLoadAll(loader);
      this.mappingFunction = key -> {
        try {
          return loader.load(key);
        } catch (RuntimeException e) {
          throw e;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CompletionException(e);
        } catch (Exception e) {
          throw new CompletionException(e);
        }
      };
    }

    @Override
    public CacheLoader<? super K, V> cacheLoader() {
      return loader;
    }

    @Override
    public Function<K, V> mappingFunction() {
      return mappingFunction;
    }

    @Override
    public boolean hasBulkLoader() {
      return hasBulkLoader;
    }

    @Override
    Object writeReplace() {
      @SuppressWarnings("unchecked")
      SerializationProxy<K, V> proxy = (SerializationProxy<K, V>) super.writeReplace();
      proxy.loader = loader;
      return proxy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }
  }

  /* ---------------- Async Loading Cache -------------- */

  static final class UnboundedLocalAsyncLoadingCache<K, V>
      extends LocalAsyncLoadingCache<UnboundedLocalCache<K, CompletableFuture<V>>, K, V>
      implements Serializable {
    private static final long serialVersionUID = 1;

    Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    UnboundedLocalAsyncLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(new UnboundedLocalCache<>((Caffeine<K, CompletableFuture<V>>) builder, true), loader);
    }

    @Override
    protected Policy<K, V> policy() {
      return (policy == null) ? (policy = new UnboundedPolicy<>(cache.isRecordingStats)) : policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      SerializationProxy<K, V> proxy = new SerializationProxy<>();
      proxy.isRecordingStats = cache.isRecordingStats;
      proxy.removalListener = cache.removalListener;
      proxy.ticker = cache.ticker;
      proxy.writer = cache.writer;
      proxy.loader = loader;
      proxy.async = true;
      return proxy;
    }
  }
}
