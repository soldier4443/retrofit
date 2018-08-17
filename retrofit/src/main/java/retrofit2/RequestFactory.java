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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.HTTP;
import retrofit2.http.Header;
import retrofit2.http.HeaderMap;
import retrofit2.http.Multipart;
import retrofit2.http.OPTIONS;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.PartMap;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;
import retrofit2.http.QueryName;
import retrofit2.http.Url;

import static retrofit2.Utils.methodError;
import static retrofit2.Utils.parameterError;

/**
 * 자, 이제 여기서 annotation handling의 진수를 확인할 수 있음. 잘 보고 최대한 배워보자!!
 * RequestFactory - 이 녀석의 역할은 뭘까? 말 그대로 Request를 만들어내는 공장임.
 * Factory인 이유는.. 아마도 한 번 annotation processing을 한 다음 여러 개의 request를 보낼 때
 * 이걸 이용해서 하기 때문인 것 같음.
 */

final class RequestFactory {
  // Retrofit과 Method 정의(API inteface의)를 가지고 RequestFactory를 만드는 작업.
  static RequestFactory parseAnnotations(Retrofit retrofit, Method method) {
    return new Builder(retrofit, method).build();
  }

  private final HttpUrl baseUrl;
  final String httpMethod;
  private final String relativeUrl;
  private final Headers headers;
  private final MediaType contentType;
  private final boolean hasBody;
  private final boolean isFormEncoded;
  private final boolean isMultipart;
  private final ParameterHandler<?>[] parameterHandlers;

  RequestFactory(Builder builder) {
    baseUrl = builder.retrofit.baseUrl;
    httpMethod = builder.httpMethod;
    relativeUrl = builder.relativeUrl;
    headers = builder.headers;
    contentType = builder.contentType;
    hasBody = builder.hasBody;
    isFormEncoded = builder.isFormEncoded;
    isMultipart = builder.isMultipart;
    parameterHandlers = builder.parameterHandlers;
  }

  okhttp3.Request create(@Nullable Object[] args) throws IOException {
    RequestBuilder requestBuilder = new RequestBuilder(httpMethod, baseUrl, relativeUrl, headers,
        contentType, hasBody, isFormEncoded, isMultipart);

    @SuppressWarnings("unchecked") // It is an error to invoke a method with the wrong arg types.
    ParameterHandler<Object>[] handlers = (ParameterHandler<Object>[]) parameterHandlers;

    int argumentCount = args != null ? args.length : 0;
    if (argumentCount != handlers.length) {
      throw new IllegalArgumentException("Argument count (" + argumentCount
          + ") doesn't match expected count (" + handlers.length + ")");
    }

    for (int p = 0; p < argumentCount; p++) {
      handlers[p].apply(requestBuilder, args[p]);
    }

    return requestBuilder.build();
  }

  /**
   * 역시나 Builder 패턴을 사용했음.
   * 설명을 보니 계산 비용이 매우 큰 reflection을 사용하니 한 번 만들고 재사용하는게 좋을 거라고 하네..
   *
   * Inspects the annotations on an interface method to construct a reusable service method. This
   * requires potentially-expensive reflection so it is best to build each service method only once
   * and reuse it. Builders cannot be reused.
   */
  static final class Builder {
    // Upper and lower characters, digits, underscores, and hyphens, starting with a character.
    private static final String PARAM = "[a-zA-Z][a-zA-Z0-9_-]*";
    
    // @GET("some/url/{param}") 이런걸 찾을 때 쓰는 regex
    private static final Pattern PARAM_URL_REGEX = Pattern.compile("\\{(" + PARAM + ")\\}");
    private static final Pattern PARAM_NAME_REGEX = Pattern.compile(PARAM);

    final Retrofit retrofit;
    final Method method;
    final Annotation[] methodAnnotations;
    final Annotation[][] parameterAnnotationsArray;
    final Type[] parameterTypes;

    // got - parameter processing 여부를 나타내는 boolean 값
    boolean gotField;
    boolean gotPart;
    boolean gotBody;
    boolean gotPath;
    boolean gotQuery;
    boolean gotQueryName;
    boolean gotQueryMap;
    boolean gotUrl;
    
    String httpMethod;
    boolean hasBody;
    boolean isFormEncoded;
    boolean isMultipart;
    String relativeUrl;
    
    Headers headers;
    MediaType contentType;
    Set<String> relativeUrlParamNames;
    ParameterHandler<?>[] parameterHandlers;

    Builder(Retrofit retrofit, Method method) {
      this.retrofit = retrofit;
      this.method = method;
      this.methodAnnotations = method.getAnnotations();
      this.parameterTypes = method.getGenericParameterTypes();
      this.parameterAnnotationsArray = method.getParameterAnnotations();
    }

    RequestFactory build() {
      // 메소드에 달린 annotation 처리.
      for (Annotation annotation : methodAnnotations) {
        parseMethodAnnotation(annotation);
      }

      // 파싱한거에 대해서 여러 가지 테스트
      if (httpMethod == null) {
        throw methodError(method, "HTTP method annotation is required (e.g., @GET, @POST, etc.).");
      }

      if (!hasBody) {
        if (isMultipart) {
          throw methodError(method,
              "Multipart can only be specified on HTTP methods with request body (e.g., @POST).");
        }
        if (isFormEncoded) {
          throw methodError(method, "FormUrlEncoded can only be specified on HTTP methods with "
              + "request body (e.g., @POST).");
        }
      }

      // 각 파라미터에 달린 annotation 처리.
      int parameterCount = parameterAnnotationsArray.length;
      parameterHandlers = new ParameterHandler<?>[parameterCount];
      for (int p = 0; p < parameterCount; p++) {
        Type parameterType = parameterTypes[p];
        if (Utils.hasUnresolvableType(parameterType)) {
          throw parameterError(method, p,
              "Parameter type must not include a type variable or wildcard: %s", parameterType);
        }

        Annotation[] parameterAnnotations = parameterAnnotationsArray[p];
        if (parameterAnnotations == null) {
          throw parameterError(method, p, "No Retrofit annotation found.");
        }

        parameterHandlers[p] = parseParameter(p, parameterType, parameterAnnotations);
      }

      // 파싱한거에 대해서 여러 가지 테스트
      if (relativeUrl == null && !gotUrl) {
        throw methodError(method, "Missing either @%s URL or @Url parameter.", httpMethod);
      }
      if (!isFormEncoded && !isMultipart && !hasBody && gotBody) {
        throw methodError(method, "Non-body HTTP method cannot contain @Body.");
      }
      if (isFormEncoded && !gotField) {
        throw methodError(method, "Form-encoded method must contain at least one @Field.");
      }
      if (isMultipart && !gotPart) {
        throw methodError(method, "Multipart method must contain at least one @Part.");
      }

      return new RequestFactory(this);
    }

    // 메소드 지정 어노테이션 파싱
    private void parseMethodAnnotation(Annotation annotation) {
      // REST API method별로 설정하고~
      if (annotation instanceof DELETE) {
        parseHttpMethodAndPath("DELETE", ((DELETE) annotation).value(), false);
      } else if (annotation instanceof GET) {
        parseHttpMethodAndPath("GET", ((GET) annotation).value(), false);
      } else if (annotation instanceof HEAD) {
        parseHttpMethodAndPath("HEAD", ((HEAD) annotation).value(), false);
      } else if (annotation instanceof PATCH) {
        parseHttpMethodAndPath("PATCH", ((PATCH) annotation).value(), true);
      } else if (annotation instanceof POST) {
        parseHttpMethodAndPath("POST", ((POST) annotation).value(), true);
      } else if (annotation instanceof PUT) {
        parseHttpMethodAndPath("PUT", ((PUT) annotation).value(), true);
      } else if (annotation instanceof OPTIONS) {
        parseHttpMethodAndPath("OPTIONS", ((OPTIONS) annotation).value(), false);
      } else if (annotation instanceof HTTP) {
        HTTP http = (HTTP) annotation;
        parseHttpMethodAndPath(http.method(), http.path(), http.hasBody());
      } else if (annotation instanceof retrofit2.http.Headers) {
        // 헤더 파싱하고~
        String[] headersToParse = ((retrofit2.http.Headers) annotation).value();
        if (headersToParse.length == 0) {
          throw methodError(method, "@Headers annotation is empty.");
        }
        headers = parseHeaders(headersToParse);
      } else if (annotation instanceof Multipart) {
        if (isFormEncoded) {
          throw methodError(method, "Only one encoding annotation is allowed.");
        }
        isMultipart = true;
      } else if (annotation instanceof FormUrlEncoded) {
        if (isMultipart) {
          throw methodError(method, "Only one encoding annotation is allowed.");
        }
        isFormEncoded = true;
      }
    }
  
    /**
     * 메소드 지정 annotation에서 여러 가지 값들을 추출.
     * @param hasBody - POST, PUT, PATCH는 true.
     */
    private void parseHttpMethodAndPath(String httpMethod, String value, boolean hasBody) {
      // HTTP method가 여러개인지 체크.
      if (this.httpMethod != null) {
        throw methodError(method, "Only one HTTP method is allowed. Found: %s and %s.",
            this.httpMethod, httpMethod);
      }
      this.httpMethod = httpMethod;
      this.hasBody = hasBody;

      if (value.isEmpty()) {
        return;
      }

      // path에 quert string이 포함되있을 경우 빼내야함.
      // Get the relative URL path and existing query string, if present.
      int question = value.indexOf('?');
      if (question != -1 && question < value.length() - 1) {
        // Ensure the query string does not have any named parameters.
        String queryParams = value.substring(question + 1);
        // GET("some/url/?param={param}") 이런거 잡아냄. 이건 안됨.
        Matcher queryParamMatcher = PARAM_URL_REGEX.matcher(queryParams);
        if (queryParamMatcher.find()) {
          throw methodError(method, "URL query string \"%s\" must not have replace block. "
              + "For dynamic query parameters use @Query.", queryParams);
        }
      }

      this.relativeUrl = value;
      this.relativeUrlParamNames = parsePathParameters(value);
    }

    // Content-Type: text/plain 이런 헤더들을 파싱하는거~~
    // Okhttp쪽은 잘 모르겠음.
    private Headers parseHeaders(String[] headers) {
      Headers.Builder builder = new Headers.Builder();
      for (String header : headers) {
        // colon이 있고 맨 앞이나 맨 끝이 아니어야함
        int colon = header.indexOf(':');
        if (colon == -1 || colon == 0 || colon == header.length() - 1) {
          throw methodError(method,
              "@Headers value must be in the form \"Name: Value\". Found: \"%s\"", header);
        }
        // name, value 나누고
        String headerName = header.substring(0, colon);
        String headerValue = header.substring(colon + 1).trim();
        // content-type일 때 특수 처리
        if ("Content-Type".equalsIgnoreCase(headerName)) {
          try {
            contentType = MediaType.get(headerValue);
          } catch (IllegalArgumentException e) {
            throw methodError(method, e, "Malformed content type: %s", headerValue);
          }
        } else {
          builder.add(headerName, headerValue);
        }
      }
      return builder.build();
    }
  
    // 한 파라미터에 대한 어노테이션 프로세싱 + 여러 가지 검사
    private ParameterHandler<?> parseParameter(
        int p, Type parameterType, Annotation[] annotations) {
      // 여러 어노테이션이 붙어있을 수 있으므로
      // 유효한 하나의 annotation에 해당하는 ParameterHandler만 골라냄
      ParameterHandler<?> result = null;
      for (Annotation annotation : annotations) {
        ParameterHandler<?> annotationAction = parseParameterAnnotation(
            p, parameterType, annotations, annotation);

        if (annotationAction == null) {
          continue;
        }

        // 이중 annotation 잡음.
        if (result != null) {
          throw parameterError(method, p, "Multiple Retrofit annotations found, only one allowed.");
        }

        result = annotationAction;
      }

      if (result == null) {
        throw parameterError(method, p, "No Retrofit annotation found.");
      }

      return result;
    }
  
    // 파라미터 한 개의 어노테이션 processing
    private ParameterHandler<?> parseParameterAnnotation(
        int p, Type type, Annotation[] annotations, Annotation annotation) {
      if (annotation instanceof Url) {
        // url은 두 번 이상 나올 수 없고 이것들과 같이 쓸 수 없음.
        if (gotUrl) {
          throw parameterError(method, p, "Multiple @Url method annotations found.");
        }
        if (gotPath) {
          throw parameterError(method, p, "@Path parameters may not be used with @Url.");
        }
        if (gotQuery) {
          throw parameterError(method, p, "A @Url parameter must not come after a @Query.");
        }
        if (gotQueryName) {
          throw parameterError(method, p, "A @Url parameter must not come after a @QueryName.");
        }
        if (gotQueryMap) {
          throw parameterError(method, p, "A @Url parameter must not come after a @QueryMap.");
        }
        if (relativeUrl != null) {
          throw parameterError(method, p, "@Url cannot be used with @%s URL", httpMethod);
        }

        gotUrl = true;

        // @Url이 붙은 걸로는 이 4개만 받습니다~
        // android.net.Uri가 저렇게 되있는 이유는, Retrofit이 순수 자바 프로젝트라서 ㅋㅋ
        if (type == HttpUrl.class
            || type == String.class
            || type == URI.class
            || (type instanceof Class && "android.net.Uri".equals(((Class<?>) type).getName()))) {
          return new ParameterHandler.RelativeUrl();
        } else {
          throw parameterError(method, p,
              "@Url must be okhttp3.HttpUrl, String, java.net.URI, or android.net.Uri type.");
        }

      } else if (annotation instanceof Path) {
        if (gotQuery) {
          throw parameterError(method, p, "A @Path parameter must not come after a @Query.");
        }
        if (gotQueryName) {
          throw parameterError(method, p, "A @Path parameter must not come after a @QueryName.");
        }
        if (gotQueryMap) {
          throw parameterError(method, p, "A @Path parameter must not come after a @QueryMap.");
        }
        if (gotUrl) {
          throw parameterError(method, p, "@Path parameters may not be used with @Url.");
        }
        if (relativeUrl == null) {
          throw parameterError(method, p, "@Path can only be used with relative url on @%s",
              httpMethod);
        }
        gotPath = true;

        Path path = (Path) annotation;
        String name = path.value();
        validatePathName(p, name);

        Converter<?, String> converter = retrofit.stringConverter(type, annotations);
        return new ParameterHandler.Path<>(name, converter, path.encoded());

      } else if (annotation instanceof Query) {
        Query query = (Query) annotation;
        String name = query.value();
        boolean encoded = query.encoded();

        Class<?> rawParameterType = Utils.getRawType(type);
        gotQuery = true;
        // @Query("something") List<String> 이런 상황
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          // 그냥 List면 안되겠지?
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(method, p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.Query<>(name, converter, encoded).iterable();
        } else if (rawParameterType.isArray()) {
          // @Query("something") String[] 이런 상황
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.Query<>(name, converter, encoded).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.Query<>(name, converter, encoded);
        }

      } else if (annotation instanceof QueryName) {
        QueryName query = (QueryName) annotation;
        boolean encoded = query.encoded();

        Class<?> rawParameterType = Utils.getRawType(type);
        gotQueryName = true;
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(method, p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.QueryName<>(converter, encoded).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.QueryName<>(converter, encoded).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.QueryName<>(converter, encoded);
        }

      } else if (annotation instanceof QueryMap) {
        Class<?> rawParameterType = Utils.getRawType(type);
        gotQueryMap = true;
        // 일단 맵이어야하고..
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(method, p, "@QueryMap parameter type must be Map.");
        }
        // HashMap<K, V> 같은 걸 Map<K, V>으로 만들려고 하는걸까?
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(method, p,
              "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;
        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(method, p, "@QueryMap keys must be of type String: " + keyType);
        }
        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        Converter<?, String> valueConverter =
            retrofit.stringConverter(valueType, annotations);

        return new ParameterHandler.QueryMap<>(valueConverter, ((QueryMap) annotation).encoded());

      } else if (annotation instanceof Header) {
        Header header = (Header) annotation;
        String name = header.value();

        Class<?> rawParameterType = Utils.getRawType(type);
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(method, p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.Header<>(name, converter).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.Header<>(name, converter).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.Header<>(name, converter);
        }

      } else if (annotation instanceof HeaderMap) {
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(method, p, "@HeaderMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(method, p,
              "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;
        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(method, p, "@HeaderMap keys must be of type String: " + keyType);
        }
        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        Converter<?, String> valueConverter =
            retrofit.stringConverter(valueType, annotations);

        return new ParameterHandler.HeaderMap<>(valueConverter);

      } else if (annotation instanceof Field) {
        if (!isFormEncoded) {
          throw parameterError(method, p, "@Field parameters can only be used with form encoding.");
        }
        Field field = (Field) annotation;
        String name = field.value();
        boolean encoded = field.encoded();

        gotField = true;

        Class<?> rawParameterType = Utils.getRawType(type);
        if (Iterable.class.isAssignableFrom(rawParameterType)) {
          if (!(type instanceof ParameterizedType)) {
            throw parameterError(method, p, rawParameterType.getSimpleName()
                + " must include generic type (e.g., "
                + rawParameterType.getSimpleName()
                + "<String>)");
          }
          ParameterizedType parameterizedType = (ParameterizedType) type;
          Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
          Converter<?, String> converter =
              retrofit.stringConverter(iterableType, annotations);
          return new ParameterHandler.Field<>(name, converter, encoded).iterable();
        } else if (rawParameterType.isArray()) {
          Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
          Converter<?, String> converter =
              retrofit.stringConverter(arrayComponentType, annotations);
          return new ParameterHandler.Field<>(name, converter, encoded).array();
        } else {
          Converter<?, String> converter =
              retrofit.stringConverter(type, annotations);
          return new ParameterHandler.Field<>(name, converter, encoded);
        }

      } else if (annotation instanceof FieldMap) {
        if (!isFormEncoded) {
          throw parameterError(method, p,
              "@FieldMap parameters can only be used with form encoding.");
        }
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(method, p, "@FieldMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(method, p,
              "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;
        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(method, p, "@FieldMap keys must be of type String: " + keyType);
        }
        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        Converter<?, String> valueConverter =
            retrofit.stringConverter(valueType, annotations);

        gotField = true;
        return new ParameterHandler.FieldMap<>(valueConverter, ((FieldMap) annotation).encoded());

      } else if (annotation instanceof Part) {
        if (!isMultipart) {
          throw parameterError(method, p,
              "@Part parameters can only be used with multipart encoding.");
        }
        Part part = (Part) annotation;
        gotPart = true;

        String partName = part.value();
        Class<?> rawParameterType = Utils.getRawType(type);
        // param - MultipartBody.Part
        if (partName.isEmpty()) {
          if (Iterable.class.isAssignableFrom(rawParameterType)) {
            if (!(type instanceof ParameterizedType)) {
              throw parameterError(method, p, rawParameterType.getSimpleName()
                  + " must include generic type (e.g., "
                  + rawParameterType.getSimpleName()
                  + "<String>)");
            }
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
            if (!MultipartBody.Part.class.isAssignableFrom(Utils.getRawType(iterableType))) {
              throw parameterError(method, p,
                  "@Part annotation must supply a name or use MultipartBody.Part parameter type.");
            }
            return ParameterHandler.RawPart.INSTANCE.iterable();
          } else if (rawParameterType.isArray()) {
            Class<?> arrayComponentType = rawParameterType.getComponentType();
            if (!MultipartBody.Part.class.isAssignableFrom(arrayComponentType)) {
              throw parameterError(method, p,
                  "@Part annotation must supply a name or use MultipartBody.Part parameter type.");
            }
            return ParameterHandler.RawPart.INSTANCE.array();
          } else if (MultipartBody.Part.class.isAssignableFrom(rawParameterType)) {
            return ParameterHandler.RawPart.INSTANCE;
          } else {
            throw parameterError(method, p,
                "@Part annotation must supply a name or use MultipartBody.Part parameter type.");
          }
        } else {
          // 여기서 multipart 헤더를 설정해줌.
          Headers headers =
              Headers.of("Content-Disposition", "form-data; name=\"" + partName + "\"",
                  "Content-Transfer-Encoding", part.encoding());

          if (Iterable.class.isAssignableFrom(rawParameterType)) {
            if (!(type instanceof ParameterizedType)) {
              throw parameterError(method, p, rawParameterType.getSimpleName()
                  + " must include generic type (e.g., "
                  + rawParameterType.getSimpleName()
                  + "<String>)");
            }
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type iterableType = Utils.getParameterUpperBound(0, parameterizedType);
            if (MultipartBody.Part.class.isAssignableFrom(Utils.getRawType(iterableType))) {
              throw parameterError(method, p,
                  "@Part parameters using the MultipartBody.Part must not "
                      + "include a part name in the annotation.");
            }
            // 여기서는 아까랑 다르게 왜 RequestBodyConverter를 사용할까? (아까는 StringConverter였음)
            // 이유는 바로 Multipart request 자체가 곧바로 request body를 만들기 때문.
            // 아마 Body도 마찬가지일듯?
            // 여기서 Converter를 interface로 사용했을 때의 이득을 볼 수 있음.
            // RequestBodyConverter와 StringConverter가 같은 converter를 구현하고 있기 때문에
            Converter<?, RequestBody> converter =
                retrofit.requestBodyConverter(iterableType, annotations, methodAnnotations);
            return new ParameterHandler.Part<>(headers, converter).iterable();
          } else if (rawParameterType.isArray()) {
            Class<?> arrayComponentType = boxIfPrimitive(rawParameterType.getComponentType());
            if (MultipartBody.Part.class.isAssignableFrom(arrayComponentType)) {
              throw parameterError(method, p,
                  "@Part parameters using the MultipartBody.Part must not "
                      + "include a part name in the annotation.");
            }
            Converter<?, RequestBody> converter =
                retrofit.requestBodyConverter(arrayComponentType, annotations, methodAnnotations);
            return new ParameterHandler.Part<>(headers, converter).array();
          } else if (MultipartBody.Part.class.isAssignableFrom(rawParameterType)) {
            throw parameterError(method, p,
                "@Part parameters using the MultipartBody.Part must not "
                    + "include a part name in the annotation.");
          } else {
            Converter<?, RequestBody> converter =
                retrofit.requestBodyConverter(type, annotations, methodAnnotations);
            return new ParameterHandler.Part<>(headers, converter);
          }
        }

      } else if (annotation instanceof PartMap) {
        if (!isMultipart) {
          throw parameterError(method, p,
              "@PartMap parameters can only be used with multipart encoding.");
        }
        gotPart = true;
        Class<?> rawParameterType = Utils.getRawType(type);
        if (!Map.class.isAssignableFrom(rawParameterType)) {
          throw parameterError(method, p, "@PartMap parameter type must be Map.");
        }
        Type mapType = Utils.getSupertype(type, rawParameterType, Map.class);
        if (!(mapType instanceof ParameterizedType)) {
          throw parameterError(method, p,
              "Map must include generic types (e.g., Map<String, String>)");
        }
        ParameterizedType parameterizedType = (ParameterizedType) mapType;

        Type keyType = Utils.getParameterUpperBound(0, parameterizedType);
        if (String.class != keyType) {
          throw parameterError(method, p, "@PartMap keys must be of type String: " + keyType);
        }

        Type valueType = Utils.getParameterUpperBound(1, parameterizedType);
        if (MultipartBody.Part.class.isAssignableFrom(Utils.getRawType(valueType))) {
          throw parameterError(method, p, "@PartMap values cannot be MultipartBody.Part. "
              + "Use @Part List<Part> or a different value type instead.");
        }

        Converter<?, RequestBody> converter =
            retrofit.requestBodyConverter(valueType, annotations, methodAnnotations);

        PartMap partMap = (PartMap) annotation;
        return new ParameterHandler.PartMap<>(converter, partMap.encoding());

      } else if (annotation instanceof Body) {
        if (isFormEncoded || isMultipart) {
          throw parameterError(method, p,
              "@Body parameters cannot be used with form or multi-part encoding.");
        }
        if (gotBody) {
          throw parameterError(method, p, "Multiple @Body method annotations found.");
        }

        Converter<?, RequestBody> converter;
        try {
          converter = retrofit.requestBodyConverter(type, annotations, methodAnnotations);
        } catch (RuntimeException e) {
          // Wide exception range because factories are user code.
          throw parameterError(method, e, p, "Unable to create @Body converter for %s", type);
        }
        gotBody = true;
        return new ParameterHandler.Body<>(converter);
      }

      return null; // Not a Retrofit annotation.
    }

    // @Path의 value를 검증.
    private void validatePathName(int p, String name) {
      if (!PARAM_NAME_REGEX.matcher(name).matches()) {
        throw parameterError(method, p, "@Path parameter name must match %s. Found: %s",
            PARAM_URL_REGEX.pattern(), name);
      }
      // Verify URL replacement name is actually present in the URL path.
      // 앞서서 method level annotation을 처리할 때 relativeUrlParamNames를 저장한 이유가 여기있었음.
      // 이후 parameter processing을 할 때 정의되어 있는 값과 일치하는지 검사하기 위해서.. ㄷㄷ
      if (!relativeUrlParamNames.contains(name)) {
        throw parameterError(method, p, "URL \"%s\" does not contain \"{%s}\".", relativeUrl, name);
      }
    }

    /**
     * GET("some/{param}/") 이런거 찾아서 Set으로 반환해줌 ㅎㅎ
     *
     * Gets the set of unique path parameters used in the given URI. If a parameter is used twice
     * in the URI, it will only show up once in the set.
     */
    static Set<String> parsePathParameters(String path) {
      Matcher m = PARAM_URL_REGEX.matcher(path);
      Set<String> patterns = new LinkedHashSet<>();
      while (m.find()) {
        patterns.add(m.group(1));
      }
      return patterns;
    }

    // 박싱까지 깔끔하게!
    private static Class<?> boxIfPrimitive(Class<?> type) {
      if (boolean.class == type) return Boolean.class;
      if (byte.class == type) return Byte.class;
      if (char.class == type) return Character.class;
      if (double.class == type) return Double.class;
      if (float.class == type) return Float.class;
      if (int.class == type) return Integer.class;
      if (long.class == type) return Long.class;
      if (short.class == type) return Short.class;
      return type;
    }
  }
}
