package org.srx.hook;

import android.content.Context;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

final class ThumbnailCache {
  private static final int MAX_FILES = 128;
  private static final long MAX_BYTES = 32L * 1024 * 1024;

  private ThumbnailCache() {}

  static File fileFor(Context context, int uid, Uri uri, File source) throws IOException {
    StructStat stat;
    try {
      stat = Os.stat(source.getAbsolutePath());
    } catch (ErrnoException error) {
      throw new IOException("Cannot stat redirected media: " + source, error);
    }
    // 每次请求核对目标文件版本，配置切换和同路径替换不得复用旧内容。
    String key = uid + "\n" + uri + "\n" + source.getAbsolutePath() + "\n"
        + stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size + ":"
        + stat.st_mtim.tv_sec + ":" + stat.st_mtim.tv_nsec + ":"
        + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
    File directory = new File(context.getCacheDir(), "srx-thumbnails-v1");
    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
      throw new IOException("Cannot create thumbnail cache: " + directory);
    return new File(directory, digest(key) + ".jpg");
  }

  private static String digest(String key) {
    byte[] bytes;
    try {
      bytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
    char[] hex = "0123456789abcdef".toCharArray();
    char[] result = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      result[i * 2] = hex[(bytes[i] & 255) >>> 4];
      result[i * 2 + 1] = hex[bytes[i] & 15];
    }
    return new String(result);
  }

  static void trim(File directory) {
    File[] files = directory.listFiles(file -> file.getName().matches("[0-9a-f]{64}\\.jpg"));
    if (files == null) {
      Log.w("SRX", "thumbnail cache listing failed path=" + directory);
      return;
    }
    long bytes = 0;
    for (File file : files)
      bytes += file.length();
    if (files.length <= MAX_FILES && bytes <= MAX_BYTES)
      return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    int count = files.length;
    // 只清理自有缓存；已经返回的文件描述符仍持有对应内容。
    for (File file : files) {
      if (count <= MAX_FILES && bytes <= MAX_BYTES)
        break;
      long size = file.length();
      if (file.delete()) {
        count--;
        bytes -= size;
      } else if (file.exists()) {
        Log.w("SRX", "thumbnail cache eviction failed path=" + file);
      }
    }
  }
}
