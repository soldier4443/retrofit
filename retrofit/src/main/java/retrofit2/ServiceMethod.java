/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import javax.annotation.Nullable;

import static retrofit2.Utils.methodError;

// 이 녀셕은 뭘 하는 녀석일까?
abstract class ServiceMethod<T> {
  // 1. 타입 검사 수행
  // 2. 실질적으로 Api가 정의된 interface의 메소드를 ServiceMethod로 바꾸는 작업을 수행함
  static <T> ServiceMethod<T> parseAnnotations(Retrofit retrofit, Method method) {
    // getGenericReturnType() -> return generic Type (with Type parameter)
    Type returnType = method.getGenericReturnType();
    
    // String - 일반 타입이거나
    // List<String> - ParameterizedType 이거나 등등이어야 함.
    if (Utils.hasUnresolvableType(returnType)) {
      throw methodError(method,
          "Method return type must not include a type variable or wildcard: %s", returnType);
    }
    
    if (returnType == void.class) {
      throw methodError(method, "Service methods cannot return void.");
    }

    return new HttpServiceMethod.Builder<Object, T>(retrofit, method).build();
  }

  abstract T invoke(@Nullable Object[] args);
}
