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
package retrofit2.http;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import okhttp3.HttpUrl;
import retrofit2.Retrofit;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Dynamic한 url을 지원하기 위해서 만들어진 어노테이션.
 * 일반적인 GET, POST 등에서 경로를 생략하고 대신 첫 번째 파라미터를 Url로 annotate하면 된다.
 * Retrofit#baseUrl()을 기준으로 하는 상대 경로임을 명심하자!
 * Paramter handling에는 이걸 사용한다.
 * @see retrofit2.ParameterHandler.RelativeUrl
 *
 * URL resolved against the {@linkplain Retrofit#baseUrl() base URL}.
 * <pre><code>
 * &#64;GET
 * Call&lt;ResponseBody&gt; list(@Url String url);
 * </code></pre>
 * <p>
 * See {@linkplain retrofit2.Retrofit.Builder#baseUrl(HttpUrl) base URL} for details of how
 * the value will be resolved against a base URL to create the full endpoint URL.
 */
@Documented
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface Url {
}
