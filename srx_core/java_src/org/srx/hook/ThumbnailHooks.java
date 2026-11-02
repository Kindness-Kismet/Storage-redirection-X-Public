package org.srx.hook;

import android.content.ContentProvider;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

// LSPlant 生成的回调类位于其他包，回调类和方法必须公开。
public final class ThumbnailHooks extends Hooker {
  private static final HashSet<Method> INSTALLED = new HashSet<>();
  private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
  private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();
  private final String kind;
  private final Method sourceMethod;

  private ThumbnailHooks(String kind, Method sourceMethod) {
    this.kind = kind;
    this.sourceMethod = sourceMethod;
  }

  static synchronized void installOn(Class<?> providerClass) {
    try {
      Method request = providerClass.getDeclaredMethod("ensureThumbnail", Uri.class,
          CancellationSignal.class);
      Method source = providerClass.getDeclaredMethod("queryForDataFile", Uri.class,
          CancellationSignal.class);
      source.setAccessible(true);
      Class<?> thumbnailer = Class.forName(providerClass.getName() + "$Thumbnailer", false,
          providerClass.getClassLoader());
      Method cache = thumbnailer.getDeclaredMethod("getThumbnailFile", Uri.class);
      for (Method method : thumbnailer.getDeclaredMethods()) {
        if (!"executeAsyncThumbnail".equals(method.getName()))
          continue;
        Class<?> worker = Class.forName(providerClass.getPackage().getName()
            + ".ThumbnailBackgroundThread", false, providerClass.getClassLoader());
        if (!install(worker.getDeclaredMethod("getExecutor"), "executor", source))
          return;
      }
      if (install(source, "source", source) && install(cache, "cache", source))
        install(request, "request", source);
    } catch (ReflectiveOperationException error) {
      Log.w("SRX", "thumbnail hook install failed", error);
    }
  }

  private static boolean install(Method method, String kind, Method source)
      throws ReflectiveOperationException {
    if (INSTALLED.contains(method))
      return true;
    if (!Hooker.installCallback(method, new ThumbnailHooks(kind, source), "thumbnailCallback"))
      return false;
    INSTALLED.add(method);
    Log.i("SRX", "thumbnail hook installed kind=" + kind + " method=" + method);
    return true;
  }

  static boolean isResolvingThumbnailSource() {
    Request request = REQUEST.get();
    return request != null && request.isResolvingSource;
  }

  public Object thumbnailCallback(Object[] args) throws Throwable {
    if ("request".equals(kind))
      return handleRequest(args);
    Request request = REQUEST.get();
    if (request == null)
      return callBackup(args);
    if ("executor".equals(kind)) {
      request.isAsync = true;
      return new ThumbnailExecutor((ExecutorService) callBackup(args),
          command -> contextualTask(command, request));
    }
    if ("source".equals(kind))
      return resolveSource(args, request);
    return resolveCache(args, request);
  }

  private Object handleRequest(Object[] args) throws Throwable {
    int uid = Binder.getCallingUid();
    if (uid % 100000 < 10000 || uid == android.os.Process.myUid() || !Hooker.shouldFilterCaller(uid))
      return callBackup(args);
    Request previous = REQUEST.get();
    Request request = new Request(uid, (ContentProvider) args[0],
        (CancellationSignal) args[2]);
    REQUEST.set(request);
    request.log("begin uri=" + args[1]);
    try {
      // 权限检查、媒体类型选择和编码仍由原缩略图入口执行。
      Object result = callBackup(args);
      if (request.shouldLog)
        request.log("result uri=" + args[1] + " " + describe(result));
      if (!request.isAsync && request.newCache != null)
        ThumbnailCache.trim(request.newCache.getParentFile());
      return result;
    } catch (Throwable error) {
      if (request.shouldLog)
        Log.w("SRX", request.prefix() + "failed uri=" + args[1], error);
      throw error;
    } finally {
      if (previous == null)
        REQUEST.remove();
      else
        REQUEST.set(previous);
    }
  }

  private File resolveSource(Object[] args, Request request) throws Throwable {
    Uri uri = (Uri) args[1];
    Source known = request.sources.get(uri);
    if (known != null) {
      request.log("source reuse uri=" + uri + " target=" + known.target);
      return known.target;
    }
    File original;
    request.isResolvingSource = true;
    try {
      original = (File) callBackup(args);
    } finally {
      request.isResolvingSource = false;
    }
    String path = original.getAbsolutePath();
    String rewritten = Hooker.filterPath(path, request.uid, false);
    if (Hooker.HIDDEN_ROW_SENTINEL.equals(rewritten) || "".equals(rewritten)) {
      request.log("hidden uri=" + uri + " source=" + path);
      throw new FileNotFoundException("Hidden redirected media: " + uri);
    }
    File target = rewritten == null ? original : new File(rewritten);
    Source source = new Source(original, target);
    request.sources.put(uri, source);
    request.log("source uri=" + uri + " original=" + path + " target=" + target);
    return target;
  }

  private File resolveCache(Object[] args, Request request) throws Throwable {
    Uri uri = (Uri) args[1];
    File originalCache = (File) callBackup(args);
    Source source = request.sources.get(uri);
    if (source == null) {
      try {
        sourceMethod.invoke(request.provider, uri, request.signal);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
      source = request.sources.get(uri);
    }
    if (source.original.equals(source.target)) {
      request.log("cache uri=" + uri + " mode=public path=" + originalCache);
      return originalCache;
    }
    File cache = request.caches.get(uri);
    if (cache == null) {
      cache = ThumbnailCache.fileFor(request.provider.getContext(), request.uid,
          uri, source.target);
      request.caches.put(uri, cache);
    }
    boolean exists = cache.isFile();
    if (!exists)
      request.newCache = cache;
    request.log("cache uri=" + uri + " mode=redirected public=" + originalCache
        + " path=" + cache + " exists=" + exists);
    return cache;
  }

  private static Runnable contextualTask(Runnable command, Request parent) {
    Request request = new Request(parent);
    return () -> {
      Request previous = REQUEST.get();
      REQUEST.set(request);
      request.log("worker begin");
      try {
        command.run();
      } finally {
        request.log("worker end cache=" + request.newCache);
        if (previous == null)
          REQUEST.remove();
        else
          REQUEST.set(previous);
        if (request.newCache != null)
          ThumbnailCache.trim(request.newCache.getParentFile());
      }
    };
  }

  private static String describe(Object result) {
    if (result instanceof ParcelFileDescriptor) {
      int fd = ((ParcelFileDescriptor) result).getFd();
      try {
        return "fd=" + fd + " path=" + Os.readlink("/proc/self/fd/" + fd);
      } catch (ErrnoException error) {
        return "fd=" + fd + " readlink_errno=" + error.errno;
      }
    }
    return String.valueOf(result);
  }

  private static final class Source {
    final File original;
    final File target;

    Source(File original, File target) {
      this.original = original;
      this.target = target;
    }
  }

  private static final class Request {
    final int id;
    final boolean shouldLog;
    final int uid;
    final ContentProvider provider;
    final CancellationSignal signal;
    final HashMap<Uri, Source> sources = new HashMap<>();
    final HashMap<Uri, File> caches = new HashMap<>();
    boolean isResolvingSource;
    boolean isAsync;
    File newCache;

    Request(int uid, ContentProvider provider, CancellationSignal signal) {
      this.id = REQUEST_COUNT.incrementAndGet();
      this.shouldLog = id <= 64 || id % 128 == 0;
      this.uid = uid;
      this.provider = provider;
      this.signal = signal;
    }

    Request(Request parent) {
      this.id = parent.id;
      this.shouldLog = parent.shouldLog;
      this.uid = parent.uid;
      this.provider = parent.provider;
      this.signal = parent.signal;
      this.sources.putAll(parent.sources);
      this.caches.putAll(parent.caches);
      this.newCache = parent.newCache;
    }

    String prefix() {
      return "thumbnail trace id=" + id + " tid=" + android.os.Process.myTid()
          + " uid=" + uid + " ";
    }

    void log(String message) {
      if (shouldLog)
        Log.i("SRX", prefix() + message);
    }
  }
}
