package org.srx.monitor;

import android.os.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

// 独立 Root 服务，通过媒体服务的 Binder 桥接收集事件。
public final class MonitorServer extends Binder {
  private static final String HEALTH = "/dev/srx_file_monitor_health.json";
  private static final int MAX_PENDING_EVENTS = 32768;
  private final File module;
  private final MonitorPackages packages;
  private final Handler worker;
  private final Handler bridge = new Handler(Looper.getMainLooper());
  private final ArrayList<Pending> pendingEvents = new ArrayList<>();
  private final ConcurrentHashMap<Integer, Identity> identityByUid = new ConcurrentHashMap<>();
  private final ArrayList<MonitorConnection> connections = new ArrayList<>();
  private MonitorDatabase store;
  private volatile boolean isEnabled = true;
  private long retention = MonitorProtocol.DEFAULT_RETENTION_SECONDS;
  private long errors;
  private final AtomicLong lost = new AtomicLong();
  private final ConcurrentHashMap<String, Long> lossByProducer = new ConcurrentHashMap<>();
  private long policyModified = Long.MIN_VALUE;
  private final HashMap<String, String> failureByAuthority = new HashMap<>();

  private static final class Pending {
    final long time;
    final int uid, pid, status, flags;
    final String writer, path, operation;
    Pending(long t, int u, int p, String w, String path, String op, int status, int flags) {
      this.time = t;
      this.uid = u;
      this.pid = p;
      this.writer = w;
      this.path = path;
      this.operation = op;
      this.status = status;
      this.flags = flags;
    }
  }
  private static final class Identity {
    final String[] packages;
    final String actor;
    final long expires;
    Identity(String[] packages, String actor, long expires) {
      this.packages = packages;
      this.actor = actor;
      this.expires = expires;
    }
  }

  private static void info(String text) {
    System.err.println("[SrxFileMonitor] " + text);
  }
  private static void error(String text, Throwable error) {
    info(text);
    error.printStackTrace(System.err);
  }

  private MonitorServer(File module) throws ReflectiveOperationException {
    this.module = module;
    packages = new MonitorPackages();
    HandlerThread thread = new HandlerThread("srx-monitor-store");
    thread.start();
    worker = new Handler(thread.getLooper());
    worker.post(() -> {
      try {
        File directory = new File(module, "logs/monitor");
        directory.mkdirs();
        directory.setReadable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setExecutable(true, true);
        store = new MonitorDatabase(new File(directory, "file_monitor.db"));
        tick.run();
        bridge.post(attach);
        info("root store ready; FUSE access events, 5s SQLite batches");
      } catch (Exception error) {
        error("start monitor store", error);
      }
    });
  }

  private Identity identity(int uid) {
    long now = SystemClock.elapsedRealtime();
    Identity cached = identityByUid.get(uid);
    if (cached != null && cached.expires > now)
      return cached;
    String[] names = packages.packagesForUid(uid);
    if (names == null)
      names = new String[0];
    String actor = names.length == 1 ? names[0] : "";
    if (names.length > 1) {
      String shared = packages.sharedName(uid);
      actor = "shared-" + (shared == null ? Integer.toString(uid) : shared);
    }
    Identity value = new Identity(names, actor, now + 60000);
    identityByUid.put(uid, value);
    return value;
  }

  private boolean trusted(int uid, String writer) {
    if (!MonitorProtocol.providerPackage(writer))
      return false;
    for (String name : identity(uid).packages) {
      if (writer.equals(name))
        return true;
    }
    return false;
  }

  @Override
  protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
      throws RemoteException {
    if (code == INTERFACE_TRANSACTION) {
      if (reply != null)
        reply.writeString(MonitorProtocol.DESCRIPTOR);
      return true;
    }
    if (code == MonitorProtocol.DROPPED)
      return handleDropped(data);
    if (code == MonitorProtocol.EVENT)
      return handleEvent(data);
    if (code == MonitorProtocol.CLEAR || code == MonitorProtocol.RELOAD_POLICY)
      return handleControl(code, data, reply);
    return super.onTransact(code, data, reply, flags);
  }

  private boolean handleDropped(Parcel data) {
    data.enforceInterface(MonitorProtocol.DESCRIPTOR);
    String writer = data.readString();
    String source = data.readString();
    long count = data.readLong();
    if (!trusted(Binder.getCallingUid(), writer))
      throw new SecurityException("MediaProvider loss report required");
    String key = Binder.getCallingPid() + ":" + source;
    lossByProducer.compute(key, (ignored, previousCount) -> {
      long old = previousCount == null ? 0 : previousCount;
      if (count > old)
        lost.addAndGet(count - old);
      return Math.max(old, count);
    });
    return true;
  }

  private boolean handleEvent(Parcel data) {
    data.enforceInterface(MonitorProtocol.DESCRIPTOR);
    long time = data.readLong();
    int uid = data.readInt();
    int pid = data.readInt();
    String writer = data.readString();
    String path = MonitorProtocol.storagePath(data.readString());
    String operation = data.readString();
    int status = data.readInt();
    int eventFlags = data.readInt();
    if (!isEnabled || uid < 0 || uid / 100000 != 0 || uid == 1000 || path == null)
      return true;
    if (!trusted(Binder.getCallingUid(), writer))
      throw new SecurityException("MediaProvider event required");
    synchronized (pendingEvents) {
      if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
        lost.incrementAndGet();
      } else if (isEnabled)
        pendingEvents.add(new Pending(time, uid, pid, writer, path, operation, status, eventFlags));
    }
    return true;
  }

  private boolean handleControl(int code, Parcel data, Parcel reply) {
    data.enforceInterface(MonitorProtocol.DESCRIPTOR);
    String writer = data.readString();
    if (!trusted(Binder.getCallingUid(), writer))
      throw new SecurityException("Provider bridge required");
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    final Exception[] failure = new Exception[1];
    worker.post(() -> {
      try {
        if (code == MonitorProtocol.RELOAD_POLICY) {
          policyModified = Long.MIN_VALUE;
          readPolicy();
          publishHealth();
        } else {
          synchronized (pendingEvents) {
            pendingEvents.clear();
          }
          store.clear();
        }
      } catch (Exception error) {
        failure[0] = error;
      } finally {
        done.countDown();
      }
    });
    try {
      done.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("monitor control interrupted", error);
    }
    if (failure[0] != null)
      throw new IllegalStateException("monitor control failed", failure[0]);
    if (reply != null) {
      reply.writeNoException();
      if (code == MonitorProtocol.RELOAD_POLICY)
        reply.writeInt(isEnabled ? 1 : 0);
    }
    return true;
  }

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      try {
        readPolicy();
        flushPending();
        store.prune(System.currentTimeMillis(), retention);
      } catch (Exception error) {
        errors++;
        error("save file access batch", error);
      }
      try {
        publishHealth();
      } catch (Exception error) {
        error("write monitor status", error);
      }
      worker.postDelayed(this, MonitorProtocol.FLUSH_DELAY_MS);
    }
  };

  private void flushPending() {
    ArrayList<Pending> batch;
    synchronized (pendingEvents) {
      batch = new ArrayList<>(pendingEvents);
    }
    ArrayList<MonitorRecord> records = new ArrayList<>(batch.size());
    for (Pending item : batch) {
      Identity who = identity(item.uid);
      if (!MonitorProtocol.acceptsActor(item.uid, who.packages))
        continue;
      records.add(new MonitorRecord(item.time, item.uid, item.pid, item.writer, who.actor,
          item.path, item.operation, item.status, item.flags));
    }
    store.append(records);
    synchronized (pendingEvents) {
      pendingEvents.subList(0, batch.size()).clear();
    }
  }

  private void publishHealth() throws Exception {
    JSONObject health = new JSONObject();
    health.put("version", 2);
    health.put("updated_ms", System.currentTimeMillis());
    health.put("sequence", store.latestId());
    health.put("write_errors", errors);
    health.put("lost", lost.get());
    health.put("connected", authorityByConnection.containsValue("media"));
    health.put("enabled", isEnabled);
    File temporary = new File(HEALTH + ".tmp");
    Files.write(temporary.toPath(), health.toString().getBytes(StandardCharsets.UTF_8));
    if (!temporary.renameTo(new File(HEALTH)))
      throw new IOException("publish monitor status failed");
  }

  private void readPolicy() throws Exception {
    File file = new File(module, "config/global.json");
    if (file.lastModified() == policyModified)
      return;
    JSONObject json =
        new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    boolean isPreviouslyEnabled = isEnabled;
    long nextRetention = json.optLong("file_monitor_retention_seconds", MonitorProtocol.DEFAULT_RETENTION_SECONDS);
    if (nextRetention < 0 || nextRetention > Long.MAX_VALUE / 1000)
      throw new IllegalArgumentException("Invalid monitor retention");
    isEnabled = json.optBoolean("file_monitor_enabled", true);
    retention = nextRetention;
    policyModified = file.lastModified();
    if (isPreviouslyEnabled != isEnabled) {
      if (!isEnabled)
        flushPending();
      bridge.post(() -> {
        for (MonitorConnection connection : connections) {
          try {
            configure(connection);
          } catch (Exception error) {
            error("update provider monitor policy: " + connection.authority, error);
          }
        }
      });
    }
  }

  private void configure(MonitorConnection connection) throws Exception {
    Bundle bundle = new Bundle();
    bundle.putBinder("service", this);
    bundle.putBoolean("enabled", isEnabled);
    Bundle result = connection.call(MonitorProtocol.ATTACH, bundle);
    if (result == null || result.getInt("version") != 2)
      throw new IllegalStateException("Provider monitor hook not ready: " + connection.binder);
    if (connection.authority.equals("media") && result.getInt("fuse_hooks") == 0)
      throw new IllegalStateException("MediaProvider has no installed FUSE observer");
    info("provider attached; FUSE hooks=" + result.getInt("fuse_hooks"));
  }

  private final Runnable attach = new Runnable() {
    @Override
    public void run() {
      for (String authority : new String[] {"media", "downloads"}) {
        boolean isConnected = false;
        for (MonitorConnection connection : connections)
          if (authorityByConnection.get(connection).equals(authority))
            isConnected = true;
        if (isConnected)
          continue;
        MonitorConnection connection = null;
        try {
          connection = new MonitorConnection(authority);
          configure(connection);
          final MonitorConnection current = connection;
          connection.binder.linkToDeath(()
                                            -> bridge.post(() -> {
            connections.remove(current);
            authorityByConnection.remove(current);
            current.close();
            bridge.removeCallbacks(attach);
            bridge.postDelayed(attach, 1000);
          }),
              0);
          connections.add(connection);
          authorityByConnection.put(connection, authority);
          failureByAuthority.remove(authority);
        } catch (Exception error) {
          if (connection != null)
            connection.close();
          Throwable cause = error;
          while (cause instanceof InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
          String failure = cause.toString();
          if (!failure.equals(failureByAuthority.put(authority, failure)))
            info("waiting for provider monitor bridge: " + authority + " (" + failure + ")");
        }
      }
      bridge.postDelayed(this, 5000);
    }
  };
  private final ConcurrentHashMap<MonitorConnection, String> authorityByConnection =
      new ConcurrentHashMap<>();

  // 独立 app_process 必须显式注册主 Looper。
  @SuppressWarnings("deprecation")
  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    if (args.length > 1) {
      try (MonitorConnection provider = new MonitorConnection("media")) {
        Bundle command = new Bundle();
        command.putString("command", args[1]);
        provider.call(MonitorProtocol.ATTACH + ".CONTROL", command);
      }
      return;
    }
    File module = new File(args[0]);
    FileOutputStream lockFile = new FileOutputStream(new File(module, "logs/.monitor.lock"), true);
    FileLock lock = lockFile.getChannel().tryLock();
    if (lock == null) {
      lockFile.close();
      return;
    }
    Files.write(new File("/dev/srx_file_monitor.pid").toPath(),
        Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.US_ASCII));
    new MonitorServer(module);
    Looper.loop();
    lock.release();
    lockFile.close();
  }
}
