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
package org.apache.ibatis.builder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseBuilder {
  protected final Configuration configuration;
  protected final TypeAliasRegistry typeAliasRegistry;
  protected final TypeHandlerRegistry typeHandlerRegistry;

  public BaseBuilder(Configuration configuration) {
    this.configuration = configuration;
    this.typeAliasRegistry = this.configuration.getTypeAliasRegistry();
    this.typeHandlerRegistry = this.configuration.getTypeHandlerRegistry();
  }

  public Configuration getConfiguration() {
    return configuration;
  }

  /**
   * 创建正则表达式
   *
   * @param regex
   *          指定表达式
   * @param defaultValue
   *          默认表达式
   *
   * @return 正则表达式
   */
  protected Pattern parseExpression(String regex, String defaultValue) {
    return Pattern.compile(regex == null ? defaultValue : regex);
  }

  protected Boolean booleanValueOf(String value, Boolean defaultValue) {
    return value == null ? defaultValue : Boolean.valueOf(value);
  }

  protected Integer integerValueOf(String value, Integer defaultValue) {
    return value == null ? defaultValue : Integer.valueOf(value);
  }

  protected Set<String> stringSetValueOf(String value, String defaultValue) {
    value = value == null ? defaultValue : value;
    return new HashSet<>(Arrays.asList(value.split(",")));
  }

  /**
   * 解析对应的 JdbcType 类型
   *
   * @param alias
   *          别名
   *
   * @return 对应的 JdbcType 类型
   */
  protected JdbcType resolveJdbcType(String alias) {
    try {
      return alias == null ? null : JdbcType.valueOf(alias);
    } catch (IllegalArgumentException e) {
      throw new BuilderException("Error resolving JdbcType. Cause: " + e, e);
    }
  }

  /**
   * 解析对应的 ResultSetType 类型
   *
   * @param alias
   *          别名
   *
   * @return 对应的 ResultSetType 类型
   */
  protected ResultSetType resolveResultSetType(String alias) {
    try {
      return alias == null ? null : ResultSetType.valueOf(alias);
    } catch (IllegalArgumentException e) {
      throw new BuilderException("Error resolving ResultSetType. Cause: " + e, e);
    }
  }

  /**
   * 解析对应的 ParameterMode 类型
   *
   * @param alias
   *          别名
   *
   * @return 对应的 ParameterMode 类型
   */
  protected ParameterMode resolveParameterMode(String alias) {
    try {
      return alias == null ? null : ParameterMode.valueOf(alias);
    } catch (IllegalArgumentException e) {
      throw new BuilderException("Error resolving ParameterMode. Cause: " + e, e);
    }
  }

  /**
   * 创建指定对象
   *
   * @param alias
   *          别名
   *
   * @return 指定对象
   */
  protected Object createInstance(String alias) {
    // <1> 获得对应的类型
    Class<?> clazz = resolveClass(alias);
    try {
      // <2> 创建对象
      return clazz == null ? null : clazz.getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      throw new BuilderException("Error creating instance. Cause: " + e, e);
    }
  }

  /**
   * 获得对应的类型
   *
   * @param alias
   *          别名
   *
   * @return 对应的类型
   *
   * @param <T>
   *          T
   */
  protected <T> Class<? extends T> resolveClass(String alias) {
    try {
      return alias == null ? null : resolveAlias(alias);
    } catch (Exception e) {
      throw new BuilderException("Error resolving class. Cause: " + e, e);
    }
  }

  /**
   * 从 typeHandlerRegistry 中获得或创建对应的 TypeHandler 对象
   *
   * @param javaType
   *          Java类型
   * @param typeHandlerAlias
   *          别名
   *
   * @return TypeHandler
   */
  protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, String typeHandlerAlias) {
    if (typeHandlerAlias == null) {
      return null;
    }
    Class<?> type = resolveClass(typeHandlerAlias);
    if (type != null && !TypeHandler.class.isAssignableFrom(type)) {
      throw new BuilderException(
          "Type " + type.getName() + " is not a valid TypeHandler because it does not implement TypeHandler interface");
    }
    @SuppressWarnings("unchecked") // already verified it is a TypeHandler
    Class<? extends TypeHandler<?>> typeHandlerType = (Class<? extends TypeHandler<?>>) type;
    return resolveTypeHandler(javaType, typeHandlerType);
  }

  protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, Class<? extends TypeHandler<?>> typeHandlerType) {
    if (typeHandlerType == null) {
      return null;
    }
    // javaType ignored for injected handlers see issue #746 for full detail
    // 先获得 TypeHandler 对象
    TypeHandler<?> handler = typeHandlerRegistry.getMappingTypeHandler(typeHandlerType);
    // if handler not in registry, create a new one, otherwise return directly
    // 如果不存在，进行创建 TypeHandler 对象
    return handler == null ? typeHandlerRegistry.getInstance(javaType, typeHandlerType) : handler;
  }

  protected <T> Class<? extends T> resolveAlias(String alias) {
    return typeAliasRegistry.resolveAlias(alias);
  }
}
