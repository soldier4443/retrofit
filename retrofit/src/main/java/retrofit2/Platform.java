/*
 * Copyright (C) 2013 Square, Inc.
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

import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Platform - 말 그대로 Retrofit이 실행될 플랫폼을 가리키는 것 같음.
 *
 * {@link Retrofit#create(Class)} 실행할 때 Proxy를 만드는데,
 * 이 때 InvocationHandler에서 default 메소드 여부를 가지고 그냥 default 메소드를 실행할지,
 * 아니면 어노테이션을 가지고 바인딩을 할지 결정함.
 *
 * 이 클래스의 목적
 * 1. inteface에서 default method 여부를 판별하기 위한 것.
 */
class Platform {
  private static final Platform PLATFORM = findPlatform();

  static Platform get() {
    return PLATFORM;
  }
  
  // 안드로이드, 자바에서만 있는 클래스를 찾아서 ClassNotFoundException이 일어나는지를 가지고 플랫폼을 판단함. 신박하다!
  private static Platform findPlatform() {
    try {
      Class.forName("android.os.Build");
      // SDK_INT가 0일 수 있을까? 아무튼 Android임
      if (Build.VERSION.SDK_INT != 0) {
        return new Android();
      }
    } catch (ClassNotFoundException ignored) {
    }
    try {
      Class.forName("java.util.Optional");
      return new Java8();
    } catch (ClassNotFoundException ignored) {
    }
    return new Platform();
  }

  // Platform에 따라서 달라질 수 있는, callback이 실행되어야 할 default executor를 정의함
  @Nullable Executor defaultCallbackExecutor() {
    return null;
  }
  
  // Platform에 따라서 달라질 수 있는, callback이 실행되어야 할 default call adapter를 정의함
  CallAdapter.Factory defaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
    if (callbackExecutor != null) {
      return new ExecutorCallAdapterFactory(callbackExecutor);
    }
    return DefaultCallAdapterFactory.INSTANCE;
  }

  // Default 메소드 지원하는지 체크
  boolean isDefaultMethod(Method method) {
    return false;
  }
  
  //Default 메소드 지원할 경우 실행. 아니면 exception 던짐
  @Nullable Object invokeDefaultMethod(Method method, Class<?> declaringClass, Object object,
      @Nullable Object... args) throws Throwable {
    throw new UnsupportedOperationException();
  }

  @IgnoreJRERequirement // Only classloaded and used on Java 8.
  static class Java8 extends Platform {
    @Override boolean isDefaultMethod(Method method) {
      return method.isDefault();
    }

    @Override Object invokeDefaultMethod(Method method, Class<?> declaringClass, Object object,
        @Nullable Object... args) throws Throwable {
      // Because the service interface might not be public, we need to use a MethodHandle lookup
      // that ignores the visibility of the declaringClass.
      Constructor<Lookup> constructor = Lookup.class.getDeclaredConstructor(Class.class, int.class);
      constructor.setAccessible(true);
      return constructor.newInstance(declaringClass, -1 /* trusted */)
          .unreflectSpecial(method, declaringClass)
          .bindTo(object)
          .invokeWithArguments(args);
    }
  }

  static class Android extends Platform {
    @IgnoreJRERequirement // Guarded by API check.
    @Override boolean isDefaultMethod(Method method) {
      if (Build.VERSION.SDK_INT < 24) {
        return false;
      }
      return method.isDefault();
    }

    @Override public Executor defaultCallbackExecutor() {
      return new MainThreadExecutor();
    }

    @Override CallAdapter.Factory defaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
      if (callbackExecutor == null) throw new AssertionError();
      return new ExecutorCallAdapterFactory(callbackExecutor);
    }

    // 동작을 메인 쓰레드에서 수행하는 Executor.
    static class MainThreadExecutor implements Executor {
      private final Handler handler = new Handler(Looper.getMainLooper());

      @Override public void execute(Runnable r) {
        handler.post(r);
      }
    }
  }
}
