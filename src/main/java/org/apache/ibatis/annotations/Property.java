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
package org.apache.ibatis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation that inject a property value. 属性的注解
 *
 * @since 3.4.2
 *
 * @author Kazuki Shimizu
 *
 * @see CacheNamespace
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Property {

  /**
   * Returns the property name. 属性名
   *
   * @return the property name
   */
  String name();

  /**
   * Returns the property value or placeholder. 属性值
   *
   * @return the property value or placeholder
   */
  String value();
}
