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

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.Executor;

/**
 * 그냥 우리가 일반적으로 {@code Call<String>} 이런 식으로 쓰는거.
 * {@link Platform#defaultCallAdapterFactory(Executor)}
 * 여기를 보면 default로 사용하고 있는 것을 알 수 있다.
 * 아래 설명을 보면 CallAdapter가 IO 작업과 application-level 작업에 같은 쓰레드를 사용한다고 나옴.
 * (Android에서 application-level 작업이라면 당연히 UI 작업이겠지?)
 *
 * Creates call adapters for that uses the same thread for both I/O and application-level
 * callbacks. For synchronous calls this is the application thread making the request; for
 * asynchronous calls this is a thread provided by OkHttp's dispatcher.
 */
final class DefaultCallAdapterFactory extends CallAdapter.Factory {
  static final CallAdapter.Factory INSTANCE = new DefaultCallAdapterFactory();

  @Override
  public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {
    if (getRawType(returnType) != Call.class) {
      return null;
    }

    final Type responseType = Utils.getCallResponseType(returnType);
    return new CallAdapter<Object, Call<?>>() {
      @Override public Type responseType() {
        return responseType;
      }

      @Override public Call<Object> adapt(Call<Object> call) {
        return call;
      }
    };
  }
}
