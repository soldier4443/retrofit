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
import java.lang.reflect.Method;
import java.lang.reflect.Type;

import javax.annotation.Nullable;

import okhttp3.ResponseBody;

import static retrofit2.Utils.methodError;

/**
 * ServiceMethod의 구현체.
 * 마찬가지로 Builder 패턴을 사용했음.
 *
 * 이걸 잘 보면 RequestFactory, CallFactory를 가지고 있는 것을 볼 수 있음.
 * 왜 Factory일까? - Retrofit을 보면 ServiceMethod에 대한 cache를 가지고 있음.
 * 즉 이 HttpServiceMethod는 여러 번 request를 날릴 때 재사용 된다는거고..
 * 그래서 Request"Factory"이고 Call"Factory"인 것임.
 */

/** Adapts an invocation of an interface method into an HTTP call. */
final class HttpServiceMethod<ResponseT, ReturnT> extends ServiceMethod<ReturnT> {
  private final RequestFactory requestFactory;
  private final okhttp3.Call.Factory callFactory;
  private final CallAdapter<ResponseT, ReturnT> callAdapter;
  private final Converter<ResponseBody, ResponseT> responseConverter;

  HttpServiceMethod(Builder<ResponseT, ReturnT> builder) {
    requestFactory = builder.requestFactory;
    callFactory = builder.retrofit.callFactory();
    callAdapter = builder.callAdapter;
    responseConverter = builder.responseConverter;
  }

  @Override ReturnT invoke(@Nullable Object[] args) {
    return callAdapter.adapt(
        new OkHttpCall<>(requestFactory, args, callFactory, responseConverter));
  }

  /**
   * Inspects the annotations on an interface method to construct a reusable service method. This
   * requires potentially-expensive reflection so it is best to build each service method only once
   * and reuse it. Builders cannot be reused.
   */
  static final class Builder<ResponseT, ReturnT> {
    final Retrofit retrofit;
    final Method method;

    RequestFactory requestFactory;
    Type responseType;
    Converter<ResponseBody, ResponseT> responseConverter;
    CallAdapter<ResponseT, ReturnT> callAdapter;

    Builder(Retrofit retrofit, Method method) {
      this.retrofit = retrofit;
      this.method = method;
    }

    HttpServiceMethod<ResponseT, ReturnT> build() {
      requestFactory = RequestFactory.parseAnnotations(retrofit, method);

      // call adapter retrofit에서 가져오고
      callAdapter = createCallAdapter();
      responseType = callAdapter.responseType();
      if (responseType == Response.class || responseType == okhttp3.Response.class) {
        throw methodError(method, "'"
            + Utils.getRawType(responseType).getName()
            + "' is not a valid response body type. Did you mean ResponseBody?");
      }
      
      // response converter retrofit에서 가져오고
      responseConverter = createResponseConverter();

      if (requestFactory.httpMethod.equals("HEAD") && !Void.class.equals(responseType)) {
        throw methodError(method, "HEAD method must use Void as response type.");
      }

      // 생성!
      return new HttpServiceMethod<>(this);
    }

    private CallAdapter<ResponseT, ReturnT> createCallAdapter() {
      Type returnType = method.getGenericReturnType();
      Annotation[] annotations = method.getAnnotations();
      try {
        //noinspection unchecked
        return (CallAdapter<ResponseT, ReturnT>) retrofit.callAdapter(returnType, annotations);
      } catch (RuntimeException e) { // Wide exception range because factories are user code.
        throw methodError(method, e, "Unable to create call adapter for %s", returnType);
      }
    }

    private Converter<ResponseBody, ResponseT> createResponseConverter() {
      Annotation[] annotations = method.getAnnotations();
      try {
        return retrofit.responseBodyConverter(responseType, annotations);
      } catch (RuntimeException e) { // Wide exception range because factories are user code.
        throw methodError(method, e, "Unable to create converter for %s", responseType);
      }
    }
  }
}
