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
package org.apache.ibatis.reflection.wrapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 实现 ObjectWrapper 接口，ObjectWrapper 抽象类， 为子类 BeanWrapper 和 MapWrapper 提供属性值的获取和设置的公用方法
 *
 * @author Clinton Begin
 */
public abstract class BaseWrapper implements ObjectWrapper {

  protected static final Object[] NO_ARGUMENTS = {};
  /**
   * MetaObject 对象
   */
  protected final MetaObject metaObject;

  protected BaseWrapper(MetaObject metaObject) {
    this.metaObject = metaObject;
  }

  /**
   * 获得指定属性的值
   *
   * @param prop
   *          PropertyTokenizer 对象
   * @param object
   *          指定 Object 对象
   *
   * @return 值
   */
  protected Object resolveCollection(PropertyTokenizer prop, Object object) {
    if ("".equals(prop.getName())) {
      return object;
    }
    return metaObject.getValue(prop.getName());
  }

  /**
   * 获得集合中指定位置的值
   *
   * @param prop
   *          PropertyTokenizer 对象
   * @param collection
   *          集合
   *
   * @return 值
   */
  protected Object getCollectionValue(PropertyTokenizer prop, Object collection) {
    if (collection == null) {
      throw new ReflectionException("Cannot get the value '" + prop.getIndexedName() + "' because the property '"
          + prop.getName() + "' is null.");
    }
    if (collection instanceof Map) {
      return ((Map) collection).get(prop.getIndex());
    }
    int i = Integer.parseInt(prop.getIndex());
    if (collection instanceof List) {
      return ((List) collection).get(i);
    } else if (collection instanceof Object[]) {
      return ((Object[]) collection)[i];
    } else if (collection instanceof char[]) {
      return ((char[]) collection)[i];
    } else if (collection instanceof boolean[]) {
      return ((boolean[]) collection)[i];
    } else if (collection instanceof byte[]) {
      return ((byte[]) collection)[i];
    } else if (collection instanceof double[]) {
      return ((double[]) collection)[i];
    } else if (collection instanceof float[]) {
      return ((float[]) collection)[i];
    } else if (collection instanceof int[]) {
      return ((int[]) collection)[i];
    } else if (collection instanceof long[]) {
      return ((long[]) collection)[i];
    } else if (collection instanceof short[]) {
      return ((short[]) collection)[i];
    } else {
      throw new ReflectionException("Cannot get the value '" + prop.getIndexedName() + "' because the property '"
          + prop.getName() + "' is not Map, List or Array.");
    }
  }

  /**
   * 设置集合中指定位置的值
   *
   * @param prop
   *          PropertyTokenizer 对象
   * @param collection
   *          集合
   * @param value
   *          值
   */
  protected void setCollectionValue(PropertyTokenizer prop, Object collection, Object value) {
    if (collection == null) {
      throw new ReflectionException("Cannot set the value '" + prop.getIndexedName() + "' because the property '"
          + prop.getName() + "' is null.");
    }
    if (collection instanceof Map) {
      ((Map) collection).put(prop.getIndex(), value);
    } else {
      int i = Integer.parseInt(prop.getIndex());
      if (collection instanceof List) {
        ((List) collection).set(i, value);
      } else if (collection instanceof Object[]) {
        ((Object[]) collection)[i] = value;
      } else if (collection instanceof char[]) {
        ((char[]) collection)[i] = (Character) value;
      } else if (collection instanceof boolean[]) {
        ((boolean[]) collection)[i] = (Boolean) value;
      } else if (collection instanceof byte[]) {
        ((byte[]) collection)[i] = (Byte) value;
      } else if (collection instanceof double[]) {
        ((double[]) collection)[i] = (Double) value;
      } else if (collection instanceof float[]) {
        ((float[]) collection)[i] = (Float) value;
      } else if (collection instanceof int[]) {
        ((int[]) collection)[i] = (Integer) value;
      } else if (collection instanceof long[]) {
        ((long[]) collection)[i] = (Long) value;
      } else if (collection instanceof short[]) {
        ((short[]) collection)[i] = (Short) value;
      } else {
        throw new ReflectionException("Cannot set the value '" + prop.getIndexedName() + "' because the property '"
            + prop.getName() + "' is not Map, List or Array.");
      }
    }
  }

  protected Object getChildValue(PropertyTokenizer prop) {
    MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
    if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
      return null;
    }
    return metaValue.getValue(prop.getChildren());
  }

  protected void setChildValue(PropertyTokenizer prop, Object value) {
    MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
    if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
      if (value == null) {
        // don't instantiate child path if value is null
        return;
      }
      metaValue = instantiatePropertyValue(null, new PropertyTokenizer(prop.getName()), metaObject.getObjectFactory());
    }
    metaValue.setValue(prop.getChildren(), value);
  }
}
