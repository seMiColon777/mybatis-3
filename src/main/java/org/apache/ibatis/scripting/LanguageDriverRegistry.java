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
package org.apache.ibatis.scripting;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.util.MapUtil;

/**
 * LanguageDriver 注册表
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class LanguageDriverRegistry {
  /**
   * LanguageDriver 映射
   */
  private final Map<Class<? extends LanguageDriver>, LanguageDriver> LANGUAGE_DRIVER_MAP = new HashMap<>();
  /**
   * 默认的 LanguageDriver 类
   */
  private Class<? extends LanguageDriver> defaultDriverClass;

  public void register(Class<? extends LanguageDriver> cls) {
    if (cls == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    // 创建 cls 对应的对象，并添加到 LANGUAGE_DRIVER_MAP 中
    MapUtil.computeIfAbsent(LANGUAGE_DRIVER_MAP, cls, k -> {
      try {
        return k.getDeclaredConstructor().newInstance();
      } catch (Exception ex) {
        throw new ScriptingException("Failed to load language driver for " + cls.getName(), ex);
      }
    });
  }

  public void register(LanguageDriver instance) {
    if (instance == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    // 添加到 LANGUAGE_DRIVER_MAP 中
    Class<? extends LanguageDriver> cls = instance.getClass();
    if (!LANGUAGE_DRIVER_MAP.containsKey(cls)) {
      LANGUAGE_DRIVER_MAP.put(cls, instance);
    }
  }

  public LanguageDriver getDriver(Class<? extends LanguageDriver> cls) {
    return LANGUAGE_DRIVER_MAP.get(cls);
  }

  public LanguageDriver getDefaultDriver() {
    return getDriver(getDefaultDriverClass());
  }

  public Class<? extends LanguageDriver> getDefaultDriverClass() {
    return defaultDriverClass;
  }

  /**
   * 设置 {@link #defaultDriverClass}
   *
   * @param defaultDriverClass
   *          默认的 LanguageDriver 类
   */
  public void setDefaultDriverClass(Class<? extends LanguageDriver> defaultDriverClass) {
    // 注册到 LANGUAGE_DRIVER_MAP 中
    register(defaultDriverClass);
    // 设置 defaultDriverClass 属性
    this.defaultDriverClass = defaultDriverClass;
  }

}
