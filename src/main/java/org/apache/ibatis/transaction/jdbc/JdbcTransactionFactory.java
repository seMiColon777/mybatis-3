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
package org.apache.ibatis.transaction.jdbc;

import java.sql.Connection;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * Creates {@link JdbcTransaction} instances. 实现 TransactionFactory 接口，JdbcTransaction 工厂实现类
 *
 * @author Clinton Begin
 *
 * @see JdbcTransaction
 */
public class JdbcTransactionFactory implements TransactionFactory {

  private boolean skipSetAutoCommitOnClose;

  @Override
  public void setProperties(Properties props) {
    if (props == null) {
      return;
    }
    String value = props.getProperty("skipSetAutoCommitOnClose");
    if (value != null) {
      skipSetAutoCommitOnClose = Boolean.parseBoolean(value);
    }
  }

  @Override
  public Transaction newTransaction(Connection conn) {
    // 创建 JdbcTransaction 对象
    return new JdbcTransaction(conn);
  }

  @Override
  public Transaction newTransaction(DataSource ds, TransactionIsolationLevel level, boolean autoCommit) {
    // 创建 JdbcTransaction 对象
    return new JdbcTransaction(ds, level, autoCommit, skipSetAutoCommitOnClose);
  }
}
