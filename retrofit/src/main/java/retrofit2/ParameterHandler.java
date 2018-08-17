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
import java.lang.reflect.Array;
import java.util.Map;

import javax.annotation.Nullable;

import okhttp3.Headers;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import static retrofit2.Utils.checkNotNull;

/**
 * Parameter Handler.
 * 파라미터 어노테이션에 따라서 여러 가지가 정의되어 있음.
 * (RelativeUrl, Header, Path 등등)
 */
abstract class ParameterHandler<T> {
  abstract void apply(RequestBuilder builder, @Nullable T value) throws IOException;

  // 정말 영리한 방식으로 메소드를 만들었음.
  // 일단 apply에서는 어떤 Type에 대한 처리를 기술함.
  // iterable()과 array()에서는 자신의 apply()를 이용해서 새로운 ParameterHandler를 만듦.
  // 이런 방식으로 따로 클래스를 만들지 않고 dynamic하게 만들 수 있을 뿐더러
  // 호출하는 쪽에서도 iterable인지, array인지를 손쉽게 바꿀 수 있게 됨.
  
  final ParameterHandler<Iterable<T>> iterable() {
    return new ParameterHandler<Iterable<T>>() {
      @Override void apply(RequestBuilder builder, @Nullable Iterable<T> values)
          throws IOException {
        // return을 하는게 아니라 들어온 builde에 apply를 하는 개념이라
        // null에 대해서 skip하는게 가능해짐..
        if (values == null) return; // Skip null values.

        for (T value : values) {
          ParameterHandler.this.apply(builder, value);
        }
      }
    };
  }

  final ParameterHandler<Object> array() {
    return new ParameterHandler<Object>() {
      @Override void apply(RequestBuilder builder, @Nullable Object values) throws IOException {
        if (values == null) return; // Skip null values.

        for (int i = 0, size = Array.getLength(values); i < size; i++) {
          //noinspection unchecked
          ParameterHandler.this.apply(builder, (T) Array.get(values, i));
        }
      }
    };
  }
  
  /**
   * RequestBuilder의 relativeUrl 재지정
   * @see retrofit2.http.Url
   */
  static final class RelativeUrl extends ParameterHandler<Object> {
    @Override void apply(RequestBuilder builder, @Nullable Object value) {
      checkNotNull(value, "@Url parameter is null.");
      builder.setRelativeUrl(value);
    }
  }
  
  /**
   * @see retrofit2.http.Header
   */
  static final class Header<T> extends ParameterHandler<T> {
    private final String name;
    private final Converter<T, String> valueConverter;

    Header(String name, Converter<T, String> valueConverter) {
      this.name = checkNotNull(name, "name == null");
      this.valueConverter = valueConverter;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) throws IOException {
      if (value == null) return; // Skip null values.

      String headerValue = valueConverter.convert(value);
      if (headerValue == null) return; // Skip converted but null values.

      builder.addHeader(name, headerValue);
    }
  }
  
  /**
   * @see retrofit2.http.Path
   */
  static final class Path<T> extends ParameterHandler<T> {
    private final String name;
    private final Converter<T, String> valueConverter;
    private final boolean encoded;

    Path(String name, Converter<T, String> valueConverter, boolean encoded) {
      this.name = checkNotNull(name, "name == null");
      this.valueConverter = valueConverter;
      this.encoded = encoded;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) throws IOException {
      if (value == null) {
        throw new IllegalArgumentException(
            "Path parameter \"" + name + "\" value must not be null.");
      }
      builder.addPathParam(name, valueConverter.convert(value), encoded);
    }
  }
  
  /**
   * @see retrofit2.http.Query
   */
  static final class Query<T> extends ParameterHandler<T> {
    private final String name;
    private final Converter<T, String> valueConverter;
    private final boolean encoded;

    Query(String name, Converter<T, String> valueConverter, boolean encoded) {
      this.name = checkNotNull(name, "name == null");
      this.valueConverter = valueConverter;
      this.encoded = encoded;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) throws IOException {
      if (value == null) return; // Skip null values.

      String queryValue = valueConverter.convert(value);
      if (queryValue == null) return; // Skip converted but null values

      builder.addQueryParam(name, queryValue, encoded);
    }
  }
  
  /**
   * @see retrofit2.http.QueryName
   */
  static final class QueryName<T> extends ParameterHandler<T> {
    private final Converter<T, String> nameConverter;
    private final boolean encoded;

    QueryName(Converter<T, String> nameConverter, boolean encoded) {
      this.nameConverter = nameConverter;
      this.encoded = encoded;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) throws IOException {
      if (value == null) return; // Skip null values.
      builder.addQueryParam(nameConverter.convert(value), null, encoded);
    }
  }
  
  /**
   * @see retrofit2.http.QueryMap
   */
  static final class QueryMap<T> extends ParameterHandler<Map<String, T>> {
    private final Converter<T, String> valueConverter;
    private final boolean encoded;

    QueryMap(Converter<T, String> valueConverter, boolean encoded) {
      this.valueConverter = valueConverter;
      this.encoded = encoded;
    }

    @Override void apply(RequestBuilder builder, @Nullable Map<String, T> value)
        throws IOException {
      if (value == null) {
        throw new IllegalArgumentException("Query map was null.");
      }

      for (Map.Entry<String, T> entry : value.entrySet()) {
        String entryKey = entry.getKey();
        if (entryKey == null) {
          throw new IllegalArgumentException("Query map contained null key.");
        }
        T entryValue = entry.getValue();
        if (entryValue == null) {
          throw new IllegalArgumentException(
              "Query map contained null value for key '" + entryKey + "'.");
        }

        // converter가 null을 반환할 경우를 상정한 error handling.
        String convertedEntryValue = valueConverter.convert(entryValue);
        if (convertedEntryValue == null) {
          throw new IllegalArgumentException("Query map value '"
              + entryValue
              + "' converted to null by "
              + valueConverter.getClass().getName()
              + " for key '"
              + entryKey
              + "'.");
        }

        builder.addQueryParam(entryKey, convertedEntryValue, encoded);
      }
    }
  }
  
  /**
   * @see retrofit2.http.HeaderMap
   */
  static final class HeaderMap<T> extends ParameterHandler<Map<String, T>> {
    private final Converter<T, String> valueConverter;

    HeaderMap(Converter<T, String> valueConverter) {
      this.valueConverter = valueConverter;
    }

    @Override void apply(RequestBuilder builder, @Nullable Map<String, T> value)
        throws IOException {
      if (value == null) {
        throw new IllegalArgumentException("Header map was null.");
      }

      // Map#entrySet()을 이용한 foreach.
      for (Map.Entry<String, T> entry : value.entrySet()) {
        String headerName = entry.getKey();
        if (headerName == null) {
          throw new IllegalArgumentException("Header map contained null key.");
        }
        T headerValue = entry.getValue();
        if (headerValue == null) {
          throw new IllegalArgumentException(
              "Header map contained null value for key '" + headerName + "'.");
        }
        builder.addHeader(headerName, valueConverter.convert(headerValue));
      }
    }
  }
  
  /**
   * @see retrofit2.http.Field
   */
  static final class Field<T> extends ParameterHandler<T> {
    private final String name;
    private final Converter<T, String> valueConverter;
    private final boolean encoded;

    Field(String name, Converter<T, String> valueConverter, boolean encoded) {
      this.name = checkNotNull(name, "name == null");
      this.valueConverter = valueConverter;
      this.encoded = encoded;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) throws IOException {
      if (value == null) return; // Skip null values.

      String fieldValue = valueConverter.convert(value);
      if (fieldValue == null) return; // Skip null converted values

      builder.addFormField(name, fieldValue, encoded);
    }
  }
  
  /**
   * @see retrofit2.http.FieldMap
   */
  static final class FieldMap<T> extends ParameterHandler<Map<String, T>> {
    private final Converter<T, String> valueConverter;
    private final boolean encoded;

    FieldMap(Converter<T, String> valueConverter, boolean encoded) {
      this.valueConverter = valueConverter;
      this.encoded = encoded;
    }

    @Override void apply(RequestBuilder builder, @Nullable Map<String, T> value)
        throws IOException {
      if (value == null) {
        throw new IllegalArgumentException("Field map was null.");
      }

      for (Map.Entry<String, T> entry : value.entrySet()) {
        String entryKey = entry.getKey();
        if (entryKey == null) {
          throw new IllegalArgumentException("Field map contained null key.");
        }
        T entryValue = entry.getValue();
        if (entryValue == null) {
          throw new IllegalArgumentException(
              "Field map contained null value for key '" + entryKey + "'.");
        }

        String fieldEntry = valueConverter.convert(entryValue);
        if (fieldEntry == null) {
          throw new IllegalArgumentException("Field map value '"
              + entryValue
              + "' converted to null by "
              + valueConverter.getClass().getName()
              + " for key '"
              + entryKey
              + "'.");
        }

        builder.addFormField(entryKey, fieldEntry, encoded);
      }
    }
  }
  
  /**
   * @see retrofit2.http.Part
   */
  static final class Part<T> extends ParameterHandler<T> {
    private final Headers headers;
    private final Converter<T, RequestBody> converter;

    Part(Headers headers, Converter<T, RequestBody> converter) {
      this.headers = headers;
      this.converter = converter;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) {
      if (value == null) return; // Skip null values.

      RequestBody body;
      try {
        body = converter.convert(value);
      } catch (IOException e) {
        throw new RuntimeException("Unable to convert " + value + " to RequestBody", e);
      }
      builder.addPart(headers, body);
    }
  }

  // 왜 이것만 싱글턴일까? 조금 생각을 해봤더니..
  // 이건 field가 필요하지 않음! 그러니까 서로 다른 버전의 RawPart가 필요하지 않다는거.
  // 이것만 싱글턴으로 한게 아니라 이것밖에 싱글턴으로 할 수 없었던 것..
  static final class RawPart extends ParameterHandler<MultipartBody.Part> {
    static final RawPart INSTANCE = new RawPart();

    private RawPart() {
    }

    @Override void apply(RequestBuilder builder, @Nullable MultipartBody.Part value) {
      if (value != null) { // Skip null values.
        builder.addPart(value);
      }
    }
  }
  
  /**
   * @see retrofit2.http.PartMap
   */
  static final class PartMap<T> extends ParameterHandler<Map<String, T>> {
    private final Converter<T, RequestBody> valueConverter;
    private final String transferEncoding;

    PartMap(Converter<T, RequestBody> valueConverter, String transferEncoding) {
      this.valueConverter = valueConverter;
      this.transferEncoding = transferEncoding;
    }

    @Override void apply(RequestBuilder builder, @Nullable Map<String, T> value)
        throws IOException {
      if (value == null) {
        throw new IllegalArgumentException("Part map was null.");
      }

      for (Map.Entry<String, T> entry : value.entrySet()) {
        String entryKey = entry.getKey();
        if (entryKey == null) {
          throw new IllegalArgumentException("Part map contained null key.");
        }
        T entryValue = entry.getValue();
        if (entryValue == null) {
          throw new IllegalArgumentException(
              "Part map contained null value for key '" + entryKey + "'.");
        }

        Headers headers = Headers.of(
            "Content-Disposition", "form-data; name=\"" + entryKey + "\"",
            "Content-Transfer-Encoding", transferEncoding);

        builder.addPart(headers, valueConverter.convert(entryValue));
      }
    }
  }
  
  /**
   * @see retrofit2.http.Body
   */
  static final class Body<T> extends ParameterHandler<T> {
    private final Converter<T, RequestBody> converter;

    Body(Converter<T, RequestBody> converter) {
      this.converter = converter;
    }

    @Override void apply(RequestBuilder builder, @Nullable T value) {
      if (value == null) {
        throw new IllegalArgumentException("Body parameter value must not be null.");
      }
      RequestBody body;
      try {
        body = converter.convert(value);
      } catch (IOException e) {
        throw new RuntimeException("Unable to convert " + value + " to RequestBody", e);
      }
      builder.setBody(body);
    }
  }
}
