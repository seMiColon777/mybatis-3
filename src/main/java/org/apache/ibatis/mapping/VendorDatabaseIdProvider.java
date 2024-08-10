/*
 *    Copyright 2009-2023 the original author or authors.
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
package org.apache.ibatis.mapping;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BuilderException;

/**
 * Vendor DatabaseId provider.
 * <p>
 * It returns database product name as a databaseId. If the user provides a properties it uses it to translate database
 * product name key="Microsoft SQL Server", value="ms" will return "ms". It can return null, if no database product name
 * or a properties was specified and no translation was found.
 * 实现 DatabaseIdProvider 接口，供应商数据库标识提供器实现类
 *
 * @author Eduardo Macarron
 */
public class VendorDatabaseIdProvider implements DatabaseIdProvider {
  /**
   * Properties 对象
   */
  private Properties properties;

  @Override
  public String getDatabaseId(DataSource dataSource) {
    if (dataSource == null) {
      throw new NullPointerException("dataSource cannot be null");
    }
    try {
      // 获得数据库标识
      return getDatabaseName(dataSource);
    } catch (SQLException e) {
      throw new BuilderException("Error occurred when getting DB product name.", e);
    }
  }

  @Override
  public void setProperties(Properties p) {
    this.properties = p;
  }

  private String getDatabaseName(DataSource dataSource) throws SQLException {
    // <1> 获得数据库产品名
    String productName = getDatabaseProductName(dataSource);
    if (this.properties != null) {
      // 如果产品名包含 KEY ，则返回对应的  VALUE
      return properties.entrySet().stream().filter(entry -> productName.contains((String) entry.getKey()))
          .map(entry -> (String) entry.getValue()).findFirst().orElse(null);
    }
    // <3> 不存在 properties ，则直接返回 productName
    return productName;
  }

  private String getDatabaseProductName(DataSource dataSource) throws SQLException {
    // 获得数据库连接
    try (Connection con = dataSource.getConnection()) {
      // 获得数据库产品名
      return con.getMetaData().getDatabaseProductName();
    }
  }

}
