/*
 *    Copyright 2009-2025 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.ibatis.cache.Cache;

/**
 * Weak Reference cache decorator.
 * <p>
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class WeakCache implements Cache {
  /**
   * 强引用的键的队列
   */
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  /**
   * 被 GC 回收的 WeakEntry 集合，避免被 GC。
   */
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  /**
   * 装饰的 Cache 对象
   */
  private final Cache delegate;
  private int numberOfHardLinks;
  private final ReentrantLock lock = new ReentrantLock();

  public WeakCache(Cache delegate) {
    this.delegate = delegate;
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  @Override
  public void putObject(Object key, Object value) {
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    // 添加到 delegate 中
    delegate.putObject(key, new WeakEntry(key, value, queueOfGarbageCollectedEntries));
  }

  @Override
  public Object getObject(Object key) {
    Object result = null;
    // 获得值的 WeakReference 对象
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    WeakReference<Object> weakReference = (WeakReference<Object>) delegate.getObject(key);
    if (weakReference != null) {
      // 获得值
      result = weakReference.get();
      if (result == null) {
        // 为空，从 delegate 中移除 。为空的原因是，意味着已经被 GC 回收
        delegate.removeObject(key);
        // 非空，添加到 hardLinksToAvoidGarbageCollection 中，避免被 GC
      } else {
        lock.lock();
        try {
          // 添加到 hardLinksToAvoidGarbageCollection 的队头
          hardLinksToAvoidGarbageCollection.addFirst(result);
          // 超过上限，移除 hardLinksToAvoidGarbageCollection 的队尾
          if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
            hardLinksToAvoidGarbageCollection.removeLast();
          }
        } finally {
          lock.unlock();
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    // 移除出 delegate
    @SuppressWarnings("unchecked")
    WeakReference<Object> weakReference = (WeakReference<Object>) delegate.removeObject(key);
    return weakReference == null ? null : weakReference.get();
  }

  @Override
  public void clear() {
    // 清空 hardLinksToAvoidGarbageCollection
    lock.lock();
    try {
      hardLinksToAvoidGarbageCollection.clear();
    } finally {
      lock.unlock();
    }
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    // 清空 delegate
    delegate.clear();
  }

  /**
   * 移除已经被 GC 回收的键
   */
  private void removeGarbageCollectedItems() {
    WeakEntry sv;
    while ((sv = (WeakEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      delegate.removeObject(sv.key);
    }
  }

  private static class WeakEntry extends WeakReference<Object> {
    /**
     * 键
     */
    private final Object key;

    private WeakEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      super(value, garbageCollectionQueue);
      this.key = key;
    }
  }

}
