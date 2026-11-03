package org.srx.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.srx.monitor.MonitorEventBuffer;
import org.srx.monitor.MonitorProtocol;

// 通过媒体服务收集共享存储访问，事件异步发送给独立服务。
public final class FileMonitorHooks extends Hooker {
  private static final String TAG = "SrxFileMonitor";
  private static final HashSet<Method> INSTALLED = new HashSet<>();
  private static volatile IBinder service;
  private static IBinder.DeathRecipient serviceDeath;
  private static volatile boolean isEnabled = true;
  private static Handler sender;
  private static int fuseHooks;
  private static final ThreadLocal<Boolean> INSIDE_PROVIDER = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return false;
    }
  };
  private static boolean isTransportErrorLogged;
  private static final MonitorEventBuffer<Event> EVENTS = new MonitorEventBuffer<>(8192);
  private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
  private static final String SOURCE = Long.toString(System.nanoTime());
  private static long reportedLost;
  private static final class Event {
    final int uid, flags, status;
    final long time;
    final String writer, path, operation;
    Event(int uid, int flags, int status, String writer, String path, String operation) {
      this.uid = uid;
      this.flags = flags;
      this.status = status;
      this.writer = writer;
      this.path = path;
      this.operation = operation;
      time = System.currentTimeMillis();
    }
  }
  private final ContentProvider provider;
  private final Method method;
  private final String kind;
  private final Field statusField;
  private final Field uidField;

  private FileMonitorHooks(ContentProvider provider, Method method, String kind)
      throws ReflectiveOperationException {
    this.provider = provider;
    this.method = method;
    this.kind = kind;
    statusField =
        kind.equals("onFileOpenForFuse") ? method.getReturnType().getDeclaredField("status") : null;
    if (statusField != null)
      statusField.setAccessible(true);
    uidField =
        kind.equals("onFileOpenForFuse") ? method.getReturnType().getDeclaredField("uid") : null;
    if (uidField != null)
      uidField.setAccessible(true);
  }

  public static void setInitialEnabled(boolean value) {
    isEnabled = value;
    if (!value) EVENTS.clear();
  }

  private static synchronized Handler sender() {
    if (sender == null) {
      HandlerThread thread = new HandlerThread("srx-fuse-events");
      thread.start();
      sender = new Handler(thread.getLooper());
    }
    return sender;
  }

  public static synchronized void installOn(ContentProvider provider) {
    boolean isMedia = false, isDownloads = false;
    for (Class<?> c = provider.getClass(); c != null; c = c.getSuperclass()) {
      isMedia |= c.getName().equals("com.android.providers.media.MediaProvider")
          || c.getName().equals("com.android.providers.media.module.MediaProvider");
      isDownloads |= c.getName().equals("com.android.providers.downloads.DownloadProvider");
    }
    if (!isMedia && !isDownloads)
      return;
    sender();
    try {
      Method call = provider.getClass().getMethod("call", String.class, String.class, Bundle.class);
      install(provider, call, "bridge");
      java.util.List<Method> fuseMethods = findFuseMethods(provider.getClass());
      for (Class<?> c = provider.getClass(); c != null && c != ContentProvider.class;
          c = c.getSuperclass()) {
        for (Method method : c.getDeclaredMethods()) {
          String name = method.getName();
          Class<?>[] p = method.getParameterTypes();
          if (isMedia && fuseMethods.contains(method)) {
            if (install(provider, method, name))
              fuseHooks++;
          } else if ((name.equals("insert") || name.equals("update")) && p.length > 1
              && p[0] == Uri.class && p[1] == ContentValues.class) {
            install(provider, method, (isMedia ? "media_" : "download_") + name);
          } else if (isMedia && name.equals("delete") && p.length == 3 && p[0] == Uri.class
              && (p[1] == String.class || p[1] == Bundle.class)) {
            install(provider, method, "media_delete");
          } else if (isMedia && name.equals("openFile") && p.length >= 2 && p[0] == Uri.class
              && p[1] == String.class) {
            install(provider, method, "media_open");
          }
        }
      }
      if (isMedia) {
        Class<?> daemon = Class.forName("com.android.providers.media.fuse.FuseDaemon", false,
            provider.getClass().getClassLoader());
        install(provider, daemon.getDeclaredMethod("run"), "fuse_ready");
        // 扫描广播在新旧版本分别交给 MediaService 或 MediaServiceV2，接收器是两者共同入口
        Class<?> receiver = Class.forName("com.android.providers.media.MediaReceiver", false,
            provider.getClass().getClassLoader());
        install(provider, receiver.getDeclaredMethod("onReceive", Context.class, Intent.class),
            "scan_file");
      }
      Log.i(TAG,
          "provider observer installed: " + provider.getClass().getName()
              + ", FUSE entries=" + fuseHooks);
    } catch (ReflectiveOperationException error) {
      Log.w(TAG, "provider observer installation", error);
    }
  }

  public static boolean fuseSignature(Method method) {
    Class<?>[] p = method.getParameterTypes();
    if (p.length < 2 || p[0] != String.class)
      return false;
    if (method.getName().equals("onFileOpenForFuse")) {
      return p.length >= 3 && p[1] == String.class && p[2] == int.class
          && method.getReturnType().getSimpleName().equals("FileOpenResult");
    }
    return p[1] == int.class && method.getReturnType() == int.class;
  }

  public static java.util.List<Method> findFuseMethods(Class<?> type) {
    ArrayList<Method> methods = new ArrayList<>();
    HashSet<String> signatures = new HashSet<>();
    boolean hasModernOpen = false;
    for (Class<?> c = type; c != null && c != ContentProvider.class; c = c.getSuperclass()) {
      for (Method method : c.getDeclaredMethods()) {
        String name = method.getName();
        if ((name.equals("onFileOpenForFuse") || name.equals("isOpenAllowedForFuse")
                || name.equals("insertFileIfNecessaryForFuse"))
            && fuseSignature(method)) {
          if (signatures.add(name + java.util.Arrays.toString(method.getParameterTypes()))) {
            methods.add(method);
            hasModernOpen |= name.equals("onFileOpenForFuse");
          }
        }
      }
    }
    if (hasModernOpen) {
      java.util.Iterator<Method> it = methods.iterator();
      while (it.hasNext())
        if (it.next().getName().equals("isOpenAllowedForFuse"))
          it.remove();
    }
    return methods;
  }

  private static boolean install(ContentProvider provider, Method method, String kind)
      throws ReflectiveOperationException {
    if (INSTALLED.contains(method))
      return false;
    FileMonitorHooks hooker = new FileMonitorHooks(provider, method, kind);
    if (!Hooker.installCallback(method, hooker, "monitorCallback"))
      return false;
    INSTALLED.add(method);
    return true;
  }

  public Object monitorCallback(Object[] args) throws Throwable {
    if (kind.equals("bridge"))
      return bridge(args);
    if (kind.equals("fuse_ready")) {
      Hooker.refreshProviderLibraries();
      return callBackup(args);
    }
    if (kind.equals("scan_file"))
      return scanFile(args);
    // 只读保护的 delete 与 open 需在通用 media_ 分支之前分发
    if (kind.equals("media_delete"))
      return guardedDelete(args);
    if (kind.equals("media_open"))
      return guardedOpen(args);
    if (kind.startsWith("media_") || kind.startsWith("download_"))
      return providerOperation(args);
    Object result;
    try {
      result = callBackup(args);
    } catch (Throwable original) {
      reportFuse(args, 13, -1);
      throw original;
    }
    try {
      int status = result instanceof Integer ? (Integer) result : statusField.getInt(result);
      int uid = originatingUid(kind, args, uidField == null ? -1 : uidField.getInt(result));
      // MediaStore 通过 Provider 打开文件时，FUSE 请求 UID 是 Provider 自身。
      if (status == 0)
        Hooker.captureFuseCaller(uid);
      reportFuse(args, status, uid);
    } catch (ReflectiveOperationException | RuntimeException error) {
      Log.w(TAG, "observe FUSE access result", error);
    }
    return result;
  }

  private Object bridge(Object[] args) throws Throwable {
    String methodName = (String) args[1];
    if (!MonitorProtocol.ATTACH.equals(methodName)
        && !(MonitorProtocol.ATTACH + ".CONTROL").equals(methodName))
      return callBackup(args);
    if (Binder.getCallingUid() != 0)
      throw new SecurityException("Root monitor service required");
    Bundle extras = (Bundle) args[3];
    if (MonitorProtocol.ATTACH.equals(methodName)) {
      IBinder next = extras.getBinder("service");
      if (service != next) {
        if (service != null && serviceDeath != null)
          service.unlinkToDeath(serviceDeath, 0);
        serviceDeath = () -> {
          if (service == next)
            service = null;
        };
        next.linkToDeath(serviceDeath, 0);
        service = next;
        reportedLost = 0;
      }
      isEnabled = extras.getBoolean("enabled", true);
      if (!isEnabled)
        EVENTS.clear();
      isTransportErrorLogged = false;
      requestDrain();
      Bundle result = new Bundle();
      result.putInt("version", 2);
      result.putInt("fuse_hooks", fuseHooks);
      return result;
    }
    IBinder target = service;
    if (target == null)
      throw new IllegalStateException("Monitor service is unavailable");
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(MonitorProtocol.DESCRIPTOR);
      data.writeString(provider.getContext().getPackageName());
      boolean isPolicy = "policy".equals(extras.getString("command"));
      target.transact(isPolicy ? MonitorProtocol.RELOAD_POLICY : MonitorProtocol.CLEAR, data, reply, 0);
      reply.readException();
      if (isPolicy) {
        setInitialEnabled(reply.readInt() != 0);
        requestDrain();
      }
      return Bundle.EMPTY;
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  public static int originatingUid(String kind, Object[] args, int returnedUid) {
    if (kind.equals("onFileOpenForFuse") && returnedUid >= 0)
      return returnedUid;
    return (Integer) args[kind.equals("onFileOpenForFuse") ? 3 : 2];
  }

  private void reportFuse(Object[] args, int status, int returnedUid) {
    int uid = originatingUid(kind, args, returnedUid);
    String path = (String) args[1];
    emit(uid, path, kind.equals("insertFileIfNecessaryForFuse") ? "creat" : "open", status);
  }

  // 旧扫描广播携带应用视角的映射路径，原生扫描找不到文件；改为实际落盘路径并补录直接写入
  private Object scanFile(Object[] args) throws Throwable {
    Intent intent = (Intent) args[2];
    Uri uri = intent.getData();
    if (!Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(intent.getAction()) || uri == null
        || !"file".equals(uri.getScheme()) || uri.getPath() == null
        || new java.io.File(uri.getPath()).exists())
      return callBackup(args);
    String target = Hooker.resolveRedirectPath(uri.getPath(), -1);
    if (target == null)
      return callBackup(args);
    try {
      int ownerUid = android.system.Os.stat(target).st_uid;
      intent.setData(Uri.fromFile(new java.io.File(target)));
      if (isEnabled && !isIndexed(target))
        emit(ownerUid, target, "creat", 0);
    } catch (android.system.ErrnoException missing) {
      // 映射目标也不存在时保持原广播，交给原生扫描清理
    }
    return callBackup(args);
  }

  // 先经 MediaStore 插入再发广播的写法已有插入事件，只为未登记的直接写入补录
  private boolean isIndexed(String path) {
    try (Cursor cursor = provider.query(android.provider.MediaStore.Files.getContentUri("external"),
             new String[] {"_id"}, "_data=?", new String[] {path}, null)) {
      return cursor != null && cursor.moveToFirst();
    }
  }

  // 映射路径的索引行改指实际落盘路径，否则关闭后的扫描找不到文件而删除该行
  private static void redirectDataPath(ContentValues values, int uid) {
    String path = values == null ? null : values.getAsString("_data");
    if (path == null)
      return;
    String target = Hooker.resolveRedirectPath(path, uid);
    if (target != null)
      values.put("_data", target);
  }

  // 按 ContentValues 推算请求写入的位置；未指定位置或位于其他卷时返回 null，交给插入后的兜底判定
  private static String requestedPath(ContentValues values, Uri uri, int uid) {
    String data = values.getAsString("_data");
    if (data != null)
      return data;
    String relative = values.getAsString("relative_path");
    if (relative == null || !isPrimaryVolume(uri))
      return null;
    String name = values.getAsString("_display_name");
    String dir = "/storage/emulated/" + uid / 100000 + "/"
        + (relative.endsWith("/") ? relative : relative + "/");
    return name == null ? dir : dir + name;
  }

  private static boolean isPrimaryVolume(Uri uri) {
    java.util.List<String> segments = uri.getPathSegments();
    return !segments.isEmpty()
        && (segments.get(0).equals("external") || segments.get(0).equals("external_primary"));
  }

  private boolean isReadOnly(String path, int uid) {
    if (!Hooker.isReadOnlyPath(path, uid))
      return false;
    emit(uid, path, kind, 30);
    return true;
  }

  // 插入后按实际落盘路径兜底，覆盖由 MediaProvider 选定默认目录的插入
  private boolean discardReadOnlyInsert(Uri inserted, int uid) {
    for (String path : paths(inserted, null, 0)) {
      if (!isReadOnly(path, uid))
        continue;
      provider.delete(inserted, null, null);
      return true;
    }
    return false;
  }

  // 改名或移动会改写只读目录中的文件，或把文件放进只读目录
  private boolean isReadOnlyMove(Object[] args, int uid) {
    ContentValues values = (ContentValues) args[2];
    // 移入回收站会把文件改名为 .trashed- 前缀，同样属于改写
    if (values == null
        || !(values.containsKey("_data") || values.containsKey("relative_path")
            || values.containsKey("_display_name") || values.containsKey("is_trashed")))
      return false;
    ArrayList<String> touched = paths((Uri) args[1], args, 3);
    String destination = requestedPath(values, (Uri) args[1], uid);
    if (destination != null)
      touched.add(destination);
    for (String path : touched) {
      if (isReadOnly(path, uid))
        return true;
    }
    return false;
  }

  // 任一命中行位于只读目录时整体拒绝，避免批量删除只删掉一部分
  private Object guardedDelete(Object[] args) throws Throwable {
    int uid = Binder.getCallingUid();
    if (uid == android.os.Process.myUid() || INSIDE_PROVIDER.get())
      return callBackup(args);
    INSIDE_PROVIDER.set(true);
    try {
      for (String path : paths((Uri) args[1], args, 2)) {
        if (isReadOnly(path, uid))
          return 0;
      }
      return callBackup(args);
    } finally {
      INSIDE_PROVIDER.set(false);
    }
  }

  // 只读目录中的已有文件仅允许只读打开，拒绝方式与系统无权访问时一致
  private Object guardedOpen(Object[] args) throws Throwable {
    int uid = Binder.getCallingUid();
    String mode = (String) args[2];
    if (uid == android.os.Process.myUid() || mode == null || mode.indexOf('w') < 0)
      return callBackup(args);
    for (String path : paths((Uri) args[1], null, 0)) {
      if (isReadOnly(path, uid))
        throw new java.io.FileNotFoundException("read-only: " + path);
    }
    return callBackup(args);
  }

  private Object providerOperation(Object[] args) throws Throwable {
    int uid = Binder.getCallingUid();
    boolean isForeignMedia = kind.startsWith("media_") && uid != android.os.Process.myUid();
    if (isForeignMedia)
      redirectDataPath((ContentValues) args[2], uid);
    if (uid == android.os.Process.myUid() || INSIDE_PROVIDER.get())
      return callBackup(args);
    // 能推算位置的插入必须在执行前拒绝，MediaProvider 插入时会先逐级创建父目录
    boolean isForeignInsert = isForeignMedia && kind.equals("media_insert");
    String requestedLocation = isForeignInsert && args[2] != null
        ? requestedPath((ContentValues) args[2], (Uri) args[1], uid)
        : null;
    if (requestedLocation != null && isReadOnly(requestedLocation, uid))
      return null;
    if (isForeignMedia && kind.equals("media_update") && isReadOnlyMove(args, uid))
      return 0;
    if (!isEnabled && !isForeignMedia)
      return callBackup(args);
    INSIDE_PROVIDER.set(true);
    try {
      Uri uri = (Uri) args[1];
      ContentValues values = (ContentValues) args[2];
      ArrayList<String> before = new ArrayList<>();
      if (isEnabled && kind.endsWith("update"))
        before = paths(uri, args, 3);
      Object result = callBackup(args);
      if (isForeignInsert && requestedLocation == null && result instanceof Uri
          && discardReadOnlyInsert((Uri) result, uid))
        return null;
      if (!isEnabled)
        return result;
      try {
        if (kind.endsWith("insert") && result instanceof Uri) {
          ArrayList<String> inserted = paths((Uri) result, null, 0);
          if (inserted.isEmpty() && values != null) {
            String requested = values.getAsString("_data");
            if (requested == null && kind.equals("download_insert")) {
              String hint = values.getAsString("hint");
              if (hint != null && hint.startsWith("file://"))
                requested = Uri.parse(hint).getPath();
            }
            String scoped = MonitorProtocol.storagePath(requested);
            if (scoped != null)
              inserted.add(scoped);
          }
          for (String path : inserted) emit(uid, path, kind, 0);
        } else if (result instanceof Integer && ((Integer) result) > 0) {
          String destination = values == null ? null : values.getAsString("_data");
          for (String path : before) emit(uid, destination == null ? path : destination, kind, 0);
        }
      } catch (RuntimeException error) {
        Log.w(TAG, "observe provider access", error);
      }
      return result;
    } finally {
      INSIDE_PROVIDER.set(false);
    }
  }

  // selectionIndex 为 args 中选择条件或 Bundle 的位置，insert/update 与 delete 的参数布局不同
  private ArrayList<String> paths(Uri uri, Object[] args, int selectionIndex) {
    ArrayList<String> paths = new ArrayList<>();
    String selection = null;
    String[] selectionArgs = null;
    if (args != null && args.length > selectionIndex) {
      Object condition = args[selectionIndex];
      if (condition instanceof Bundle) {
        Bundle b = (Bundle) condition;
        selection = b.getString("android:query-arg-sql-selection");
        selectionArgs = b.getStringArray("android:query-arg-sql-selection-args");
      } else if (condition instanceof String) {
        selection = (String) condition;
        if (args.length > selectionIndex + 1)
          selectionArgs = (String[]) args[selectionIndex + 1];
      }
    }
    try (Cursor cursor =
             provider.query(uri, new String[] {"_data"}, selection, selectionArgs, null)) {
      if (cursor != null)
        while (cursor.moveToNext()) {
          String path = MonitorProtocol.storagePath(cursor.getString(0));
          if (path != null)
            paths.add(path);
        }
    } catch (RuntimeException error) {
      Log.w(TAG, "resolve provider access path", error);
    }
    return paths;
  }

  private void emit(int uid, String input, String operation, int status) {
    if (!isEnabled || uid < 0 || uid / 100000 != 0 || uid == 1000)
      return;
    String path = MonitorProtocol.storagePath(input);
    if (path == null) return;
    int flags = status == 0 ? MonitorProtocol.SUCCESS : MonitorProtocol.FAILED;
    if (Math.abs(status) == 13 || Math.abs(status) == 1 || Math.abs(status) == 30)
      flags |= MonitorProtocol.BLOCKED;
    if (status == 17 || status == -17)
      flags = MonitorProtocol.EXISTS;
    if (operation.equals("creat") || operation.endsWith("insert"))
      flags |= MonitorProtocol.CREATE;
    final String writer = provider.getContext().getPackageName();
    EVENTS.offer(new Event(uid, flags, status, writer, path, operation));
    requestDrain();
  }

  private static void requestDrain() {
    if (service != null && DRAIN_SCHEDULED.compareAndSet(false, true))
      sender().post(FileMonitorHooks::drain);
  }

  private static void drain() {
    Event event;
    while (isEnabled && (event = EVENTS.peek()) != null) {
      IBinder target = service;
      if (target == null) {
        finishDrain();
        return;
      }
      Parcel data = Parcel.obtain();
      try {
        long lost = EVENTS.lost();
        if (lost != reportedLost) {
          Parcel status = Parcel.obtain();
          try {
            status.writeInterfaceToken(MonitorProtocol.DESCRIPTOR);
            status.writeString(event.writer);
            status.writeString(SOURCE);
            status.writeLong(lost);
            if (!target.transact(MonitorProtocol.DROPPED, status, null, IBinder.FLAG_ONEWAY))
              throw new RemoteException("Monitor loss report was not accepted");
            reportedLost = lost;
          } finally {
            status.recycle();
          }
        }
        data.writeInterfaceToken(MonitorProtocol.DESCRIPTOR);
        data.writeLong(event.time);
        data.writeInt(event.uid);
        data.writeInt(android.os.Process.myPid());
        data.writeString(event.writer);
        data.writeString(event.path);
        data.writeString(event.operation);
        data.writeInt(event.status);
        data.writeInt(event.flags);
        if (!target.transact(MonitorProtocol.EVENT, data, null, IBinder.FLAG_ONEWAY))
          throw new RemoteException("Monitor event was not accepted");
        EVENTS.delivered(event);
      } catch (RemoteException error) {
        if (!isTransportErrorLogged) {
          isTransportErrorLogged = true;
          Log.w(TAG, "send file access event", error);
        }
        // 重试期间保留调度标记，避免通道拥塞时堆积重试任务。
        sender().postDelayed(FileMonitorHooks::drain, 1000);
        return;
      } finally {
        data.recycle();
      }
    }
    finishDrain();
  }

  private static void finishDrain() {
    DRAIN_SCHEDULED.set(false);
    if (isEnabled && EVENTS.peek() != null)
      requestDrain();
  }
}
