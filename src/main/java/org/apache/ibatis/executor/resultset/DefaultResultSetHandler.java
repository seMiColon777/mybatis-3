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
package org.apache.ibatis.executor.resultset;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.util.MapUtil;

/**
 * 实现 ResultSetHandler 接口，默认的 ResultSetHandler 实现类
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERRED = new Object();

  private final Executor executor;
  private final Configuration configuration;
  private final MappedStatement mappedStatement;
  private final RowBounds rowBounds;
  private final ParameterHandler parameterHandler;
  /**
   * 用户指定的用于处理结果的处理器
   */
  private final ResultHandler<?> resultHandler;
  private final BoundSql boundSql;
  private final TypeHandlerRegistry typeHandlerRegistry;
  private final ObjectFactory objectFactory;
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  // 自动映射的缓存
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();
  private final Map<String, List<String>> constructorAutoMappingColumns = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  // 自动映射的缓存
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  private static class UnMappedColumnAutoMapping {
    /**
     * 字段名
     */
    private final String column;
    /**
     * 属性名
     */
    private final String property;
    /**
     * TypeHandler 处理器
     */
    private final TypeHandler<?> typeHandler;
    /**
     * 是否为基本属性
     */
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler,
      ResultHandler<?> resultHandler, BoundSql boundSql, RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam)
      throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());
    // <1> 多 ResultSet 的结果集合，每个 ResultSet 对应一个 Object 对象
    final List<Object> multipleResults = new ArrayList<>();

    int resultSetCount = 0;
    // <2> 获得首个 ResultSet 对象，并封装成 ResultSetWrapper 对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);
    // <3> 获得 ResultMap 数组
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    int resultMapCount = resultMaps.size();
    // <3.1> 校验
    validateResultMapsCount(rsw, resultMapCount);
    while (rsw != null && resultMapCount > resultSetCount) {
      // <4.1> 获得 ResultMap 对象
      ResultMap resultMap = resultMaps.get(resultSetCount);
      // <4.2> 处理 ResultSet ，将结果添加到 multipleResults 中
      handleResultSet(rsw, resultMap, multipleResults, null);
      // <4.3> 获得下一个 ResultSet 对象，并封装成 ResultSetWrapper 对象
      rsw = getNextResultSet(stmt);
      // <4.4> 清理
      cleanUpAfterHandlingResultSet();
      resultSetCount++;
    }
    // <5> 因为 `mappedStatement.resultSets` 只在存储过程中使用，本系列暂时不考虑，忽略即可
    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }
    // <6> 如果是 multipleResults 单元素，则取首元素返回
    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());
    // 获得首个 ResultSet 对象，并封装成 ResultSetWrapper 对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);
    // 游标方式的查询，只允许一个 ResultSet 对象。因此，resultMaps 数组的数量，元素只能有一个
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }
    // 获得 ResultMap 对象，后创建 DefaultCursor 对象
    ResultMap resultMap = resultMaps.get(0);
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    ResultSet rs = stmt.getResultSet();
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB)
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else if (stmt.getUpdateCount() == -1) {
        // no more results. Must be no resultset
        break;
      }
    }
    // 将 ResultSet 对象，封装成 ResultSetWrapper 对象
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        // DO NOT try to 'improve' the condition even if IDE tells you to!
        // It's important that getUpdateCount() is called here.
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          ResultSet rs = stmt.getResultSet();
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException(
          "A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
              + "'. 'resultType' or 'resultMap' must be specified when there is no corresponding method.");
    }
  }

  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults,
      ResultMapping parentMapping) throws SQLException {
    try {
      // <1> 暂时忽略，因为只有存储过程的情况，调用该方法，parentMapping 为非空
      if (parentMapping != null) {
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else if (resultHandler == null) {
        // <2> 如果没有自定义的 resultHandler ，则创建默认的 DefaultResultHandler 对象
        // <2> 创建 DefaultResultHandler 对象
        DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
        // <3> 处理 ResultSet 返回的每一行 Row
        handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
        // <4> 添加 defaultResultHandler 的处理的结果，到 multipleResults 中
        multipleResults.add(defaultResultHandler.getResultList());
      } else {
        // <3> 处理 ResultSet 返回的每一行 Row
        handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
      }
    } finally {
      // issue #228 (close resultsets)
      // 关闭 ResultSet 对象
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler,
      RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // <1> 处理嵌套映射的情况
    if (resultMap.hasNestedResultMaps()) {
      // 校验不要使用 RowBounds
      ensureNoRowBounds();
      // 校验不要使用自定义的 resultHandler
      checkResultHandler();
      // 处理嵌套映射的结果
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
      // <2> 处理简单映射的情况
    } else {
      // <2.1> 处理简单映射的结果
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null
        && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException(
          "Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
              + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException(
          "Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
              + "Use safeResultHandlerEnabled=false setting to bypass this check "
              + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap,
      ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // <1> 创建 DefaultResultContext 对象
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    // <2> 获得 ResultSet 对象，并跳到 rowBounds 指定的开始位置
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);
    // <3> 循环
    // 是否继续处理 ResultSet
    // ResultSet 是否已经关闭
    // ResultSet 是否还有下一条
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // <4> 根据该行记录以及 ResultMap.discriminator ，决定映射使用的 ResultMap 对象
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // <5> 根据最终确定的 ResultMap 对 ResultSet 中的该行记录进行映射，得到映射后的结果对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      // <6> 将映射创建的结果对象添加到 ResultHandler.resultList 中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue,
      ResultMapping parentMapping, ResultSet rs) throws SQLException {
    // 这个情况，只有存储过程会出现
    if (parentMapping != null) {
      linkToParents(rs, parentMapping, rowValue);
    } else {
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  // 调用 ResultHandler ，进行结果的处理
  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object> */)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext,
      Object rowValue) {
    // 设置结果对象到 resultContext 中
    resultContext.nextResultObject(rowValue);
    // <x> 使用 ResultHandler 处理结果。
    // 如果使用 DefaultResultHandler 实现类的情况，会将映射创建的结果对象添加到 ResultHandler.resultList 中保存
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      // 直接跳转到指定开始的位置
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      // 循环，不断跳到开始的位置
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    // <1> 创建 ResultLoaderMap 对象
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    // <2> 创建映射后的结果对象
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    // <3> 如果 hasTypeHandlerForResultObject(rsw, resultMap.getType()) 返回 true ，意味着 rowValue 是基本类型，无需执行下列逻辑
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // <4> 创建 MetaObject 对象，用于访问 rowValue 对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      // <5> foundValues 代表，是否成功映射任一属性。若成功，则为 true ，若失败，则为 false
      boolean foundValues = this.useConstructorMappings;
      // <6.1> 判断是否开启自动映射功能
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        // <6.2> 自动映射未明确的列
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      // <7> 映射 ResultMap 中明确映射的列
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      // <8> ↑↑↑ 至此，当前 ResultSet 的该行记录的数据，已经完全映射到结果对象 rowValue 的对应属性种
      foundValues = lazyLoader.size() > 0 || foundValues;
      // <9> 如果没有成功映射任意属性，则置空 rowValue 对象
      // 当然，如果开启 `configuration.returnInstanceForEmptyRow` 属性，则不置空。默认情况下，该值为 false
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix,
      Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      putAncestor(rowValue, resultMapId);
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      ancestorObjects.remove(resultMapId);
    } else {
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        putAncestor(rowValue, resultMapId);
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true)
            || foundValues;
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    // 判断是否开启自动映射功能
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    }
    // 内嵌查询或嵌套映射时
    if (isNested) {
      return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
    } else { // 普通映射
      return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
      ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    // 获得 mapped 的字段的名字的数组
    final Set<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    // 遍历 ResultMapping 数组
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    for (ResultMapping propertyMapping : propertyMappings) {
      // 获得字段名
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        column = null;
      }
      if (propertyMapping.isCompositeResult() // 组合
          || column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))
          || propertyMapping.getResultSet() != null) { // 存储过程
        // <1> 获得指定字段的值
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader,
            columnPrefix);
        // issue #541 make property optional
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        }
        // 存储过程相关
        if (value == DEFERRED) {
          foundValues = true;
          continue;
        }
        // 标记获取到任一属性
        if (value != null) {
          foundValues = true;
        }
        // 设置到 parameterObject 中，通过 metaObject
        if (value != null
            || configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive()) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
      ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    // <2> 内嵌查询，获得嵌套查询的值
    if (propertyMapping.getNestedQueryId() != null) {
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    }
    // 存储过程相关
    if (propertyMapping.getResultSet() != null) {
      addPendingChildRelation(rs, metaResultObject, propertyMapping); // TODO is that OK?
      return DEFERRED;
    } else {
      // 普通，直接获得指定字段的值
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      return typeHandler.getResult(rs, column);
    }
  }

  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap,
      MetaObject metaObject, String columnPrefix) throws SQLException {
    // 生成 autoMappingsCache 的 KEY
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    // 从缓存 autoMappingsCache 中，获得 UnMappedColumnAutoMapping 数组
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    // 如果获取不到，则进行初始化
    if (autoMapping == null) {
      autoMapping = new ArrayList<>();
      // 获得未 mapped 的字段的名字的数组
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      // Remove the entry to release the memory
      List<String> mappedInConstructorAutoMapping = constructorAutoMappingColumns.remove(mapKey);
      if (mappedInConstructorAutoMapping != null) {
        unmappedColumnNames.removeAll(mappedInConstructorAutoMapping);
      }
      // 遍历 unmappedColumnNames 数组
      for (String columnName : unmappedColumnNames) {
        // 获得属性名
        String propertyName = columnName;
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          if (!columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            continue;
          }
          propertyName = columnName.substring(columnPrefix.length());
        }
        // 从结果对象的 metaObject 中，获得对应的属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        // 获得到属性名，并且可以进行设置
        if (property != null && metaObject.hasSetter(property)) {
          // 排除已映射的属性
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          // 获得属性的类型
          final Class<?> propertyType = metaObject.getSetterType(property);
          // 判断是否有对应的 TypeHandler 对象。如果有，则创建 UnMappedColumnAutoMapping 对象，并添加到 autoMapping 中
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            autoMapping
                .add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
            // 如果没有，则执行 AutoMappingUnknownColumnBehavior 对应的逻辑
          } else {
            configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName, property,
                propertyType);
          }
          // 如果没有属性，或者无法设置，则则执行 AutoMappingUnknownColumnBehavior 对应的逻辑
        } else {
          configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName,
              property != null ? property : propertyName, null);
        }
      }
      // 添加到缓存中
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
      String columnPrefix) throws SQLException {
    // <1> 获得 UnMappedColumnAutoMapping 数组
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    if (!autoMapping.isEmpty()) {
      // <2> 遍历 UnMappedColumnAutoMapping 数组
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        // 获得指定字段的值
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        // 若非空，标记 foundValues 有值
        if (value != null) {
          foundValues = true;
        }
        // 设置到 parameterObject 中，通过 metaObject
        if (value != null || configuration.isCallSettersOnNulls() && !mapping.primitive) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),
        parentMapping.getForeignColumn());
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping)
      throws SQLException {
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),
        parentMapping.getColumn());
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    List<PendingRelation> relations = MapUtil.computeIfAbsent(pendingRelations, cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else if (!previous.equals(parentMapping)) {
      throw new ExecutorException("Two different properties are mapped to the same resultSet");
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns)
      throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader,
      String columnPrefix) throws SQLException {
    // <1> useConstructorMappings ，表示是否使用构造方法创建该结果对象。此处将其重置
    this.useConstructorMappings = false; // reset previous mapping result
    // 记录使用的构造方法的参数类型的数组
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    // 记录使用的构造方法的参数值的数组
    final List<Object> constructorArgs = new ArrayList<>();
    // <2> 创建映射后的结果对象
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // <3> 如果有内嵌的查询，并且开启延迟加载，则创建结果对象的代理对象
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration,
              objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    // <4> 判断是否使用构造方法创建该结果对象
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    return resultObject;
  }

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes,
      List<Object> constructorArgs, String columnPrefix) throws SQLException {
    final Class<?> resultType = resultMap.getType();
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    // 下面，分成四种创建结果对象的情况
    // <1> 情况一，如果有对应的 TypeHandler 对象，则意味着是基本类型，直接创建对结果应对象
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
    }
    // 情况二，如果 ResultMap 中，如果定义了 `<constructor />` 节点，则通过反射调用该构造方法，创建对应结果对象
    if (!constructorMappings.isEmpty()) {
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs,
          columnPrefix);
      // 情况三，如果有默认的无参的构造方法，则使用该构造方法，创建对应结果对象
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      return objectFactory.create(resultType);
      // 情况四，通过自动映射的方式查找合适的构造方法，后使用该构造方法，创建对应结果对象
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      return createByConstructorSignature(rsw, resultMap, columnPrefix, resultType, constructorArgTypes,
          constructorArgs);
    }
    // 不支持，抛出 ExecutorException 异常
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType,
      List<ResultMapping> constructorMappings, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
      String columnPrefix) {
    // 获得到任一的属性值。即，只要一个结果对象，有一个属性非空，就会设置为 true
    boolean foundValues = false;
    for (ResultMapping constructorMapping : constructorMappings) {
      // 获得参数类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      // 获得数据库的字段名
      final String column = constructorMapping.getColumn();
      // 获得属性值
      final Object value;
      try {
        // 如果是内嵌的查询，则获得内嵌的值
        if (constructorMapping.getNestedQueryId() != null) {
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
          // 如果是内嵌的 resultMap ，则递归 getRowValue 方法，获得对应的属性值
        } else if (constructorMapping.getNestedResultMapId() != null) {
          String constructorColumnPrefix = getColumnPrefix(columnPrefix, constructorMapping);
          final ResultMap resultMap = resolveDiscriminatedResultMap(rsw.getResultSet(),
              configuration.getResultMap(constructorMapping.getNestedResultMapId()), constructorColumnPrefix);
          value = getRowValue(rsw, resultMap, constructorColumnPrefix);
          // 最常用的情况，直接使用 TypeHandler 获取当前 ResultSet 的当前行的指定字段的值
        } else {
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      // 添加到 constructorArgTypes 和 constructorArgs 中
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      // 判断是否获得到属性值
      foundValues = value != null || foundValues;
    }
    // 查找 constructorArgTypes 对应的构造方法
    // 查找到后，传入 constructorArgs 作为参数，创建结果对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Object createByConstructorSignature(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix,
      Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
    return applyConstructorAutomapping(rsw, resultMap, columnPrefix, resultType, constructorArgTypes, constructorArgs,
        findConstructorForAutomapping(resultType, rsw).orElseThrow(() -> new ExecutorException(
            "No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames())));
  }

  private Optional<Constructor<?>> findConstructorForAutomapping(final Class<?> resultType, ResultSetWrapper rsw) {
    // <1> 获得所有构造方法
    Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    if (constructors.length == 1) {
      return Optional.of(constructors[0]);
    }
    Optional<Constructor<?>> annotated = Arrays.stream(constructors)
        .filter(x -> x.isAnnotationPresent(AutomapConstructor.class)).reduce((x, y) -> {
          throw new ExecutorException("@AutomapConstructor should be used in only one constructor.");
        });
    if (annotated.isPresent()) {
      return annotated;
    }
    if (configuration.isArgNameBasedConstructorAutoMapping()) {
      // Finding-best-match type implementation is possible,
      // but using @AutomapConstructor seems sufficient.
      throw new ExecutorException(MessageFormat.format(
          "'argNameBasedConstructorAutoMapping' is enabled and the class ''{0}'' has multiple constructors, so @AutomapConstructor must be added to one of the constructors.",
          resultType.getName()));
    } else {
      return Arrays.stream(constructors).filter(x -> findUsableConstructorByArgTypes(x, rsw.getJdbcTypes())).findAny();
    }
  }

  private boolean findUsableConstructorByArgTypes(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes.length != jdbcTypes.size()) {
      return false;
    }
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Object applyConstructorAutomapping(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix,
      Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor)
      throws SQLException {
    boolean foundValues = false;
    if (configuration.isArgNameBasedConstructorAutoMapping()) {
      foundValues = applyArgNameBasedConstructorAutoMapping(rsw, resultMap, columnPrefix, constructorArgTypes,
          constructorArgs, constructor, foundValues);
    } else {
      foundValues = applyColumnOrderBasedConstructorAutomapping(rsw, constructorArgTypes, constructorArgs, constructor,
          foundValues);
    }
    return foundValues || configuration.isReturnInstanceForEmptyRow()
        ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private boolean applyColumnOrderBasedConstructorAutomapping(ResultSetWrapper rsw, List<Class<?>> constructorArgTypes,
      List<Object> constructorArgs, Constructor<?> constructor, boolean foundValues) throws SQLException {
    Class<?>[] parameterTypes = constructor.getParameterTypes();
    for (int i = 0; i < parameterTypes.length; i++) {
      Class<?> parameterType = parameterTypes[i];
      String columnName = rsw.getColumnNames().get(i);
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    return foundValues;
  }

  private boolean applyArgNameBasedConstructorAutoMapping(ResultSetWrapper rsw, ResultMap resultMap,
      String columnPrefix, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor,
      boolean foundValues) throws SQLException {
    List<String> missingArgs = null;
    Parameter[] params = constructor.getParameters();
    for (Parameter param : params) {
      boolean columnNotFound = true;
      Param paramAnno = param.getAnnotation(Param.class);
      String paramName = paramAnno == null ? param.getName() : paramAnno.value();
      for (String columnName : rsw.getColumnNames()) {
        if (columnMatchesParam(columnName, paramName, columnPrefix)) {
          Class<?> paramType = param.getType();
          TypeHandler<?> typeHandler = rsw.getTypeHandler(paramType, columnName);
          Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
          constructorArgTypes.add(paramType);
          constructorArgs.add(value);
          final String mapKey = resultMap.getId() + ":" + columnPrefix;
          if (!autoMappingsCache.containsKey(mapKey)) {
            MapUtil.computeIfAbsent(constructorAutoMappingColumns, mapKey, k -> new ArrayList<>()).add(columnName);
          }
          columnNotFound = false;
          foundValues = value != null || foundValues;
        }
      }
      if (columnNotFound) {
        if (missingArgs == null) {
          missingArgs = new ArrayList<>();
        }
        missingArgs.add(paramName);
      }
    }
    if (foundValues && constructorArgs.size() < params.length) {
      throw new ExecutorException(MessageFormat.format(
          "Constructor auto-mapping of ''{1}'' failed " + "because ''{0}'' were not found in the result set; "
              + "Available columns are ''{2}'' and mapUnderscoreToCamelCase is ''{3}''.",
          missingArgs, constructor, rsw.getColumnNames(), configuration.isMapUnderscoreToCamelCase()));
    }
    return foundValues;
  }

  private boolean columnMatchesParam(String columnName, String paramName, String columnPrefix) {
    if (columnPrefix != null) {
      if (!columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
        return false;
      }
      columnName = columnName.substring(columnPrefix.length());
    }
    return paramName
        .equalsIgnoreCase(configuration.isMapUnderscoreToCamelCase() ? columnName.replace("_", "") : columnName);
  }

  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix)
      throws SQLException {
    final Class<?> resultType = resultMap.getType();
    // 获得字段名
    final String columnName;
    if (!resultMap.getResultMappings().isEmpty()) {
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      columnName = rsw.getColumnNames().get(0);
    }
    // 获得 TypeHandler 对象
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    // 获得 ResultSet 的指定字段的值
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix)
      throws SQLException {
    // <1> 获得内嵌查询的编号
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    // <1> 获得内嵌查询的 MappedStatement 对象
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    // <1> 获得内嵌查询的参数类型
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    // <2> 获得内嵌查询的参数对象
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping,
        nestedQueryParameterType, columnPrefix);
    Object value = null;
    // <3> 执行查询
    if (nestedQueryParameterObject != null) {
      // <3.1> 获得 BoundSql 对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      // <3.2> 获得 CacheKey 对象
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT,
          nestedBoundSql);
      final Class<?> targetType = constructorMapping.getJavaType();
      // <3.3> 创建 ResultLoader 对象
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery,
          nestedQueryParameterObject, targetType, key, nestedBoundSql);
      // <3.3> 加载结果
      value = resultLoader.loadResult();
    }
    return value;
  }

  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
      ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    // 获得内嵌查询的编号
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    // 获得属性名
    final String property = propertyMapping.getProperty();
    // 获得内嵌查询的 MappedStatement 对象
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    // 获得内嵌查询的参数类型
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    // 获得内嵌查询的参数对象
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping,
        nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获得 BoundSql 对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      // 获得 CacheKey 对象
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT,
          nestedBoundSql);
      final Class<?> targetType = propertyMapping.getJavaType();
      // <1> 检查缓存中已存在
      if (executor.isCached(nestedQuery, key)) { // 有缓存
        // <2.1> 创建 DeferredLoad 对象，并通过该 DeferredLoad 对象从缓存中加载结采对象
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        // <2.2> 返回已定义
        value = DEFERRED;
        // 检查缓存中不存在
      } else { // 无缓存
        // <3.1> 创建 ResultLoader 对象
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery,
            nestedQueryParameterObject, targetType, key, nestedBoundSql);
        // <3.2> 如果要求延迟加载，则延迟加载
        if (propertyMapping.isLazy()) {
          // 如果该属性配置了延迟加载，则将其添加到 `ResultLoader.loaderMap` 中，等待真正使用时再执行嵌套查询并得到结果对象
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          // 返回已定义
          value = DEFERRED;
          // <3.3> 如果不要求延迟加载，则直接执行加载对应的值
        } else {
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  // 获得内嵌查询的参数类型
  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
      String columnPrefix) throws SQLException {
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
    return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
  }

  // 获得普通类型的内嵌查询的参数对象
  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
      String columnPrefix) throws SQLException {
    // 获得 TypeHandler 对象
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    // 获得指定字段的值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  // 获得组合类型的内嵌查询的参数对象
  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
      String columnPrefix) throws SQLException {
    // 创建参数对象
    final Object parameterObject = instantiateParameterObject(parameterType);
    // 创建参数对象的 MetaObject 对象，可对其进行访问
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    // 遍历组合的所有字段
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      // 获得属性类型
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      // 获得对应的 TypeHandler 对象
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      // 获得指定字段的值
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      // 设置到 parameterObject 中，通过 metaObject
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        // 标记 parameterObject 非空对象
        foundValues = true;
      }
    }
    // 返回参数对象
    return foundValues ? parameterObject : null;
  }

  // 创建参数对象
  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    }
    if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix)
      throws SQLException {
    // 记录已经处理过的 Discriminator 对应的 ResultMap 的编号
    Set<String> pastDiscriminators = new HashSet<>();
    // 如果存在 Discriminator 对象，则基于其获得 ResultMap 对象
    Discriminator discriminator = resultMap.getDiscriminator();
    // 因为 Discriminator 可以嵌套 Discriminator ，所以是一个递归的过程
    while (discriminator != null) {
      // 获得 Discriminator 的指定字段，在 ResultSet 中该字段的值
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      // 从 Discriminator 获取该值对应的 ResultMap 的编号
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      // 如果不存在，直接结束循环
      if (!configuration.hasResultMap(discriminatedMapId)) {
        break;
      }
      // 如果存在，则使用该 ResultMap 对象
      // 获得该 ResultMap 对象
      resultMap = configuration.getResultMap(discriminatedMapId);
      // 判断，如果出现“重复”的情况，结束循环
      Discriminator lastDiscriminator = discriminator;
      discriminator = resultMap.getDiscriminator();
      if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
        break;
      }
    }
    return resultMap;
  }

  /**
   * 获得 ResultSet 的指定字段的值
   *
   * @param rs
   *          ResultSet 对象
   * @param discriminator
   *          Discriminator 对象
   * @param columnPrefix
   *          字段名的前缀
   *
   * @return 指定字段的值
   */
  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix)
      throws SQLException {
    final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  /**
   * 拼接指定字段的前缀
   *
   * @param columnName
   *          字段的名字
   * @param prefix
   *          前缀
   *
   * @return prefix + columnName
   */
  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap,
      ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);
    Object rowValue = previousRowValue;
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
      String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException(
              "Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw)
      throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    }
    if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix.toUpperCase(Locale.ENGLISH))) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix)
      throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMap.getId());
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
      List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) {
      if (resultMapping.isSimple()) {
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        Set<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
      String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (!column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          continue;
        }
        property = column.substring(columnPrefix.length());
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);
    if (propertyValue == null) {
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        if (objectFactory.isCollection(type)) {
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException(
            "Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e,
            e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue;
    }
    return null;
  }

  // 判断是否结果对象是否有 TypeHandler 对象
  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    // 如果返回的字段只有一个，则直接判断该字段是否有 TypeHandler 对象
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    // 判断 resultType 是否有对应的 TypeHandler 对象
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
