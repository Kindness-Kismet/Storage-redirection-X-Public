package org.srx.monitor;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import java.lang.reflect.Method;

// Root 进程通过系统接口获取内容提供者。
public final class MonitorConnection implements AutoCloseable {
  private final Object activityManager;
  private final Object provider;
  final String authority;
  private final IBinder token = new Binder();
  public final IBinder binder;

  public MonitorConnection(String authority) throws Exception {
    this.authority = authority;
    IBinder activity = (IBinder) Class.forName("android.os.ServiceManager")
                           .getMethod("waitForService", String.class)
                           .invoke(null, "activity");
    if (activity == null)
      throw new IllegalStateException("activity service is unavailable");
    activityManager = Class.forName("android.app.IActivityManager$Stub")
                          .getMethod("asInterface", IBinder.class)
                          .invoke(null, activity);
    if (activityManager == null)
      throw new IllegalStateException("activity manager is unavailable");
    Method acquire = Class.forName("android.app.IActivityManager")
                         .getMethod("getContentProviderExternal", String.class, int.class,
                             IBinder.class, String.class);
    Object holder = acquire.invoke(activityManager, authority, 0, token, "srx-file-monitor");
    if (holder == null)
      throw new IllegalStateException("Provider is unavailable: " + authority);
    provider = holder.getClass().getField("provider").get(holder);
    binder = ((IInterface) provider).asBinder();
  }

  public Bundle call(String method, Bundle extras) throws Exception {
    Method call = Class.forName("android.content.IContentProvider")
                      .getMethod("call", AttributionSource.class, String.class, String.class,
                          String.class, Bundle.class);
    AttributionSource source =
        new AttributionSource.Builder(Process.myUid()).setPackageName("root").build();
    return (Bundle) call.invoke(provider, source, authority, method, null, extras);
  }

  @Override
  public void close() {
    try {
      Class.forName("android.app.IActivityManager")
          .getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
          .invoke(activityManager, authority, token, 0);
    } catch (ReflectiveOperationException e) {
      android.util.Log.w("SrxFileMonitor", "release provider", e);
    }
  }
}
