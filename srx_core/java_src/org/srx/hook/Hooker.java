// SRX Hooker 类：LSPlant Java method hook 的 Java 侧上下文。

package org.srx.hook;

import android.database.Cursor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class Hooker {
  static final String HIDDEN_ROW_SENTINEL = "SRX_HIDDEN_ROW";
  private static final ArrayList<Hooker> HOOKS = new ArrayList<>();
  private static final HashSet<String> HOOKED_QUERY_CLASSES = new HashSet<>();
  private static volatile boolean QUERY_HOOK_PENDING = true;
  private static int QUERY_LOG_COUNT;
  private static int CURSOR_LOG_COUNT;
  private static int FILTER_LOG_COUNT;
  private static int INSTALL_LOG_COUNT;

  public Method backup;
  private Member target;

  private native Method doHook(Member target, Method callback);
  private native boolean doUnhook(Member target);

  public Object callback(Object[] args) throws Throwable {
    int callerUid = android.os.Binder.getCallingUid();
    Object[] actualArgs = unwrapArgs(args);
    logQueryArgs(this, actualArgs, callerUid);
    ProjectionPatch projectionPatch = ProjectionPatch.apply(args, actualArgs);
    QueryIntent queryIntent = QueryIntent.from(actualArgs);
    Object result = onMediaProviderQuery(this, args);
    if (result instanceof Cursor) {
      return FilteringCursor.wrap((Cursor)result, callerUid,
                                  projectionPatch.visibleColumns,
                                  queryIntent.preserveMissingTargets);
    }
    logQueryResult(result);
    return result;
  }

  // ContentProvider.attachInfo 的 hook 回调：记录真实 provider，再按原路径安装 query hook
  public Object attachInfoCallback(Object[] args) throws Throwable {
    Object result = callBackup(args);
    if (QUERY_HOOK_PENDING && args.length >= 1 && args[0] != null) {
      Class<?> clazz = args[0].getClass();
      logAttachInfoCandidate(clazz, args);
      tryInstallQueryHook(clazz);
      if (QUERY_HOOK_PENDING) {
        tryLoadAndHookMediaProvider(clazz.getClassLoader());
      }
    }
    return result;
  }

  public Object callBackup(Object[] args) throws Throwable {
    if (backup == null)
      return null;
    Object receiver = args.length == 0 ? null : args[0];
    Object[] actualArgs = new Object[args.length > 0 ? args.length - 1 : 0];
    if (actualArgs.length > 0) {
      System.arraycopy(args, 1, actualArgs, 0, actualArgs.length);
    }
    try {
      return backup.invoke(receiver, actualArgs);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause != null)
        throw cause;
      throw e;
    }
  }

  public boolean unhook() { return target != null && doUnhook(target); }

  // Try already loaded MediaProvider classes first, then keep attachInfo as a fallback.
  public static boolean installMediaProviderHook() {
    logInstallInfo("java hook install begin process=" + currentProcessName() +
                   " pid=" + android.os.Process.myPid() +
                   " uid=" + android.os.Process.myUid());
    boolean directHooked = tryDirectProviderHook();
    boolean attachHooked = installAttachInfoHook();
    return directHooked || attachHooked;
  }

  private static boolean installAttachInfoHook() {
    try {
      Class<?> cpClass = Class.forName("android.content.ContentProvider");
      Method attachInfo = findAttachInfo(cpClass);
      if (attachInfo == null) {
        logInstallWarn("java hook attachInfo method missing");
        return false;
      }
      Method callback =
          Hooker.class.getDeclaredMethod("attachInfoCallback", Object[].class);
      callback.setAccessible(true);
      Hooker hooker = new Hooker();
      Method backup = hooker.doHook(attachInfo, callback);
      if (backup == null) {
        logInstallWarn("java hook attachInfo failed " + describeMethod(attachInfo));
        return false;
      }
      backup.setAccessible(true);
      hooker.backup = backup;
      hooker.target = attachInfo;
      HOOKS.add(hooker);
      logInstallInfo("java hook attachInfo ok " + describeMethod(attachInfo));
      return true;
    } catch (Throwable t) {
      logInstallWarn("java hook attachInfo error", t);
      return false;
    }
  }

  private static Method findAttachInfo(Class<?> cpClass) {
    for (Method m : cpClass.getDeclaredMethods()) {
      if (!"attachInfo".equals(m.getName()))
        continue;
      Class<?>[] params = m.getParameterTypes();
      if (params.length == 2 &&
          "android.content.Context".equals(params[0].getName()) &&
          "android.content.pm.ProviderInfo".equals(params[1].getName())) {
        return m;
      }
    }
    return null;
  }

  private static synchronized boolean tryInstallQueryHook(Class<?> clazz) {
    if (!QUERY_HOOK_PENDING)
      return true;
    boolean matchedMediaProvider = false;
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      String name = c.getName();
      if (!isMediaProviderClass(name))
        continue;
      matchedMediaProvider = true;
      if (!HOOKED_QUERY_CLASSES.add(name)) {
        logInstallInfo("java hook query class already tried " + name);
        continue;
      }
      logInstallInfo("java hook media provider matched class=" + name +
                     " loader=" + describeClassLoader(c.getClassLoader()));
      if (!installQueryOn(c))
        logInstallWarn("java hook query still pending class=" + name);
    }
    if (!matchedMediaProvider)
      logInstallInfo("java hook attachInfo skip provider=" + clazz.getName() +
                     " loader=" + describeClassLoader(clazz.getClassLoader()));
    return !QUERY_HOOK_PENDING;
  }

  private static boolean isMediaProviderClass(String name) {
    return "com.android.providers.media.MediaProvider".equals(name) ||
        "com.android.providers.media.module.MediaProvider".equals(name);
  }

  private static boolean tryDirectProviderHook() {
    boolean hookedAny = false;
    for (String name : mediaProviderClassCandidates()) {
      try {
        Class<?> clazz = Class.forName(name);
        logInstallInfo("java hook direct provider class=" + name +
                       " loader=" + describeClassLoader(clazz.getClassLoader()));
        if (tryInstallQueryHook(clazz))
          hookedAny = true;
      } catch (ClassNotFoundException ignored) {
      } catch (Throwable t) {
        logInstallWarn("java hook direct provider error " + name, t);
      }
    }
    return hookedAny || !QUERY_HOOK_PENDING;
  }

  private static void tryLoadAndHookMediaProvider(ClassLoader loader) {
    if (loader == null)
      return;
    for (String name : mediaProviderClassCandidates()) {
      try {
        Class<?> clazz = loader.loadClass(name);
        if (clazz == null)
          continue;
        logInstallInfo("java hook load provider class=" + name +
                       " loader=" + describeClassLoader(clazz.getClassLoader()));
        tryInstallQueryHook(clazz);
      } catch (ClassNotFoundException ignored) {
      } catch (Throwable t) {
        logInstallWarn("java hook load provider error " + name, t);
      }
      if (!QUERY_HOOK_PENDING)
        return;
    }
  }

  private static String[] mediaProviderClassCandidates() {
    return new String[] {
        "com.android.providers.media.MediaProvider",
        "com.android.providers.media.module.MediaProvider"
    };
  }

  private static boolean installQueryOn(Class<?> clazz) {
    boolean hooked = false;
    boolean sawQuery = false;
    try {
      Method callback = Hooker.class.getDeclaredMethod("callback", Object[].class);
      callback.setAccessible(true);
      for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
        for (Method method : c.getDeclaredMethods()) {
          if (!"query".equals(method.getName()))
            continue;
          sawQuery = true;
          String sig = describeMethod(method);
          Hooker hooker = new Hooker();
          Method backup = hooker.doHook(method, callback);
          if (backup == null) {
            logInstallWarn("java hook query failed " + sig);
            continue;
          }
          backup.setAccessible(true);
          hooker.backup = backup;
          hooker.target = method;
          HOOKS.add(hooker);
          QUERY_HOOK_PENDING = false;
          hooked = true;
          logInstallInfo("java hook query ok " + sig);
        }
      }
      if (!sawQuery)
        logInstallWarn("java hook query method missing " + clazz.getName());
    } catch (Throwable t) {
      logInstallWarn("java hook query error " + clazz.getName(), t);
    }
    return hooked;
  }

  private static String describeMethod(Method method) {
    StringBuilder sb = new StringBuilder();
    sb.append(method.getDeclaringClass().getName())
        .append('#')
        .append(method.getName())
        .append('(');
    Class<?>[] params = method.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      if (i > 0)
        sb.append(',');
      sb.append(params[i].getName());
    }
    sb.append(')');
    return sb.toString();
  }

  private static void logAttachInfoCandidate(Class<?> clazz, Object[] args) {
    String providerInfo = "unknown";
    if (args.length >= 3 && args[2] != null) {
      providerInfo = String.valueOf(args[2]);
    }
    logInstallInfo("java hook attachInfo provider=" + clazz.getName() +
                   " loader=" + describeClassLoader(clazz.getClassLoader()) +
                   " info=" + providerInfo);
  }

  private static String describeClassLoader(ClassLoader loader) {
    if (loader == null)
      return "bootstrap";
    return loader.getClass().getName() + "@" +
        Integer.toHexString(System.identityHashCode(loader));
  }

  private static String currentProcessName() {
    try {
      return android.app.Application.getProcessName();
    } catch (Throwable ignored) {
      return "unknown";
    }
  }

  private static void logInstallInfo(String message) {
    if (INSTALL_LOG_COUNT >= 32)
      return;
    INSTALL_LOG_COUNT++;
    android.util.Log.i("SRX", message);
  }

  private static void logInstallWarn(String message) {
    if (INSTALL_LOG_COUNT >= 32)
      return;
    INSTALL_LOG_COUNT++;
    android.util.Log.w("SRX", message);
  }

  private static void logInstallWarn(String message, Throwable t) {
    if (INSTALL_LOG_COUNT >= 32)
      return;
    INSTALL_LOG_COUNT++;
    android.util.Log.w("SRX", message, t);
  }

  private static native Object onMediaProviderQuery(Hooker hooker,
                                                    Object[] args)
      throws Throwable;
  static native String filterPath(String path, int callerUid,
                                  boolean preserveMissingTarget);

  static void logCursor(String stage, Cursor cursor, int pathColumn,
                        int before, int after, int callerUid) {
    if (callerUid == android.os.Process.myUid())
      return;
    if (CURSOR_LOG_COUNT >= 64)
      return;
    CURSOR_LOG_COUNT++;
    try {
      android.util.Log.i(
          "SRX", "java cursor stage=" + stage + " caller_uid=" + callerUid +
                     " class=" + cursor.getClass().getName() + " count=" +
                     before + " after=" + after + " pathColumn=" + pathColumn +
                     " columns=" + Arrays.toString(cursor.getColumnNames()));
    } catch (Throwable ignored) {
    }
  }

  static void logFilter(String reason, long id, int callerUid) {
    if (callerUid == android.os.Process.myUid())
      return;
    if (FILTER_LOG_COUNT >= 64)
      return;
    FILTER_LOG_COUNT++;
    try {
      android.util.Log.i("SRX", "java cursor " + reason +
                                    " caller_uid=" + callerUid + " id=" + id);
    } catch (Throwable ignored) {
    }
  }

  private static void logQueryArgs(Hooker hooker, Object[] args,
                                   int callerUid) {
    if (callerUid == android.os.Process.myUid())
      return;
    if (QUERY_LOG_COUNT >= 64)
      return;
    QUERY_LOG_COUNT++;
    try {
      String targetSig = hooker != null && hooker.target instanceof Method
                             ? describeMethod((Method)hooker.target)
                             : "unknown";
      android.util.Log.i(
          "SRX", "java query"
                     + " target=" + targetSig + " caller_uid=" + callerUid +
                     " caller_pid=" + android.os.Binder.getCallingPid() +
                     " args=" + describeQueryArgs(args));
    } catch (Throwable ignored) {
    }
  }

  private static void logQueryResult(Object result) {
    if (CURSOR_LOG_COUNT >= 64)
      return;
    CURSOR_LOG_COUNT++;
    try {
      android.util.Log.i(
          "SRX", "java query result=" +
                     (result == null ? "null" : result.getClass().getName()));
    } catch (Throwable ignored) {
    }
  }

  private static Object[] unwrapArgs(Object[] args) {
    if (args == null || args.length == 0)
      return args;
    if (args.length == 1 && args[0] instanceof Object[])
      return (Object[])args[0];
    Object[] actual = new Object[args.length > 0 ? args.length - 1 : 0];
    if (actual.length > 0) {
      System.arraycopy(args, 1, actual, 0, actual.length);
    }
    return actual;
  }

  private static String describeQueryArgs(Object[] args) {
    if (args == null)
      return "null";
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < args.length; i++) {
      if (i > 0)
        sb.append(", ");
      Object arg = args[i];
      if (arg instanceof android.net.Uri) {
        sb.append("uri=").append(String.valueOf(arg));
      } else if (arg instanceof String[]) {
        sb.append("String[]").append(Arrays.toString((String[])arg));
      } else if (arg == null) {
        sb.append("null");
      } else {
        sb.append(arg.getClass().getName());
      }
    }
    sb.append(']');
    return sb.toString();
  }
}
