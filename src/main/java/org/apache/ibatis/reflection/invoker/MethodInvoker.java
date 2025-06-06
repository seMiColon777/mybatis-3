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
package org.apache.ibatis.reflection.invoker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.ibatis.reflection.Reflector;

/**
 * 实现 Invoker 接口，指定方法的调用器
 *
 * @author Clinton Begin
 */
public class MethodInvoker implements Invoker {
  /**
   * 类型
   */
  private final Class<?> type;
  /**
   * 指定方法
   */
  private final Method method;

  public MethodInvoker(Method method) {
    this.method = method;
    // 参数大小为 1 时，一般是 setting 方法，设置 type 为方法参数[0]
    if (method.getParameterTypes().length == 1) {
      type = method.getParameterTypes()[0];
      // 否则，一般是 getting 方法，设置 type 为返回类型
    } else {
      type = method.getReturnType();
    }
  }

  /**
   * 执行指定方法
   *
   * @param target
   *          目标
   * @param args
   *          参数
   *
   * @return
   *
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   */
  @Override
  public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
    try {
      return method.invoke(target, args);
    } catch (IllegalAccessException e) {
      if (Reflector.canControlMemberAccessible()) {
        method.setAccessible(true);
        return method.invoke(target, args);
      }
      throw e;
    }
  }

  @Override
  public Class<?> getType() {
    return type;
  }
}
