package org.srx.hook;

import android.database.AbstractCursor;
import android.database.Cursor;
import java.util.ArrayList;
import java.util.Locale;

final class FilteringCursor extends AbstractCursor {
  private static final int ANDROID_APP_UID_START = 10000;
  private static final int ANDROID_USER_ID_OFFSET = 100000;
  private static final String[] PUBLIC_MEDIA_ROOTS = {
      "DCIM", "Pictures", "Movies", "Download", "Documents", "Music"
  };

  private final Cursor base;
  private final int[] rows;
  private final String[] rewrites;
  private final int pathColumn;
  private final String[] relativeRewrites;
  private final int relativePathColumn;
  private final String[] bucketIdRewrites;
  private final int bucketIdColumn;
  private final String[] visibleColumns;

  private FilteringCursor(Cursor base, int[] rows, String[] rewrites,
                          int pathColumn, String[] relativeRewrites,
                          int relativePathColumn, String[] bucketIdRewrites,
                          int bucketIdColumn, String[] visibleColumns) {
    this.base = base;
    this.rows = rows;
    this.rewrites = rewrites;
    this.pathColumn = pathColumn;
    this.relativeRewrites = relativeRewrites;
    this.relativePathColumn = relativePathColumn;
    this.bucketIdRewrites = bucketIdRewrites;
    this.bucketIdColumn = bucketIdColumn;
    this.visibleColumns = visibleColumns;
  }

  static Cursor wrap(Cursor base, int callerUid, String[] visibleColumns,
                     boolean preserveMissingTargets) {
    if (base == null || base.isClosed())
      return base;
    int pathColumn = findPathColumn(base);
    int relativePathColumn = findColumnIgnoreCase(base, "relative_path");
    int bucketIdColumn = findColumnIgnoreCase(base, "bucket_id");
    int idColumn = findIdColumn(base);
    int count = safeCount(base);
    Hooker.logCursor("wrap", base, pathColumn, count, count, callerUid);
    if (count <= 0)
      return base;
    boolean hideOpaqueRows = isAnyPublicMediaRootHidden(callerUid);
    if (pathColumn < 0 && relativePathColumn < 0 && bucketIdColumn < 0 &&
        hideOpaqueRows) {
      Hooker.logFilter("filter_opaque", -1, callerUid);
      return new FilteringCursor(base, new int[0], new String[count], -1,
                                 new String[count], -1, new String[count], -1,
                                 visibleColumns);
    }
    if (pathColumn < 0 && relativePathColumn < 0 && bucketIdColumn < 0 &&
        idColumn < 0)
      return base;

    ArrayList<Integer> visibleRows = new ArrayList<>();
    String[] rewrites = new String[count];
    String[] relativeRewrites = new String[count];
    String[] bucketIdRewrites = new String[count];
    int oldPosition = base.getPosition();
    try {
      base.moveToPosition(-1);
      while (base.moveToNext()) {
        int row = base.getPosition();
        long id = readId(base, idColumn);
        if (pathColumn >= 0) {
          String path = base.getString(pathColumn);
          String rewritten = path == null ? null
                              : Hooker.filterPath(path, callerUid,
                                                  preserveMissingTargets);
          if (Hooker.HIDDEN_ROW_SENTINEL.equals(rewritten) ||
              (rewritten != null && rewritten.length() == 0)) {
            Hooker.logFilter("filter", id, callerUid);
            continue;
          }
          if (rewritten != null && !rewritten.equals(path)) {
            Hooker.logFilter("rewrite", id, callerUid);
            rewrites[row] = rewritten;
            String relative = isPrivateRedirectStoragePath(rewritten, callerUid)
                                  ? null
                                  : relativePathFromStoragePath(rewritten, callerUid);
            if (relative != null) {
              if (relativePathColumn >= 0)
                relativeRewrites[row] = relative;
              if (bucketIdColumn >= 0) {
                String bucketId = bucketIdFromRelativePath(relative, callerUid);
                if (bucketId != null)
                  bucketIdRewrites[row] = bucketId;
              }
            }
          }
          visibleRows.add(row);
          continue;
        }
        if (relativePathColumn >= 0) {
          String probePath = relativePathProbePath(base, relativePathColumn,
                                                  callerUid);
          if (probePath != null) {
            String rewrittenProbe =
                Hooker.filterPath(probePath, callerUid, false);
            if (isHiddenRewrite(rewrittenProbe)) {
              Hooker.logFilter("filter_relative", id, callerUid);
              continue;
            }
            if (rewrittenProbe != null && !rewrittenProbe.equals(probePath)) {
              String relative = isPrivateRedirectStoragePath(rewrittenProbe, callerUid)
                                    ? null
                                    : relativePathFromStoragePath(rewrittenProbe,
                                                                  callerUid);
              if (relative != null) {
                relativeRewrites[row] = relative;
                if (bucketIdColumn >= 0) {
                  String bucketId =
                      bucketIdFromRelativePath(relative, callerUid);
                  if (bucketId != null)
                    bucketIdRewrites[row] = bucketId;
                }
              }
            }
          }
          visibleRows.add(row);
          continue;
        }
        String aggregateProbePath = aggregateProbePath(base, callerUid);
        if ((aggregateProbePath != null &&
             isHiddenPath(aggregateProbePath, callerUid)) ||
            (aggregateProbePath == null && hideOpaqueRows)) {
          Hooker.logFilter("filter_opaque", id, callerUid);
          continue;
        }
        if (bucketIdColumn >= 0 && aggregateProbePath != null) {
          String rewrittenProbe =
              Hooker.filterPath(aggregateProbePath, callerUid, false);
          if (rewrittenProbe != null &&
              !rewrittenProbe.equals(aggregateProbePath)) {
            String bucketId = isPrivateRedirectStoragePath(rewrittenProbe,
                                                           callerUid)
                                  ? null
                                  : bucketIdFromStoragePath(rewrittenProbe,
                                                            callerUid);
            if (bucketId != null)
              bucketIdRewrites[row] = bucketId;
          }
        }
        visibleRows.add(row);
      }
    } catch (Throwable ignored) {
      return base;
    } finally {
      try {
        base.moveToPosition(oldPosition);
      } catch (Throwable ignored) {
      }
    }

    Hooker.logCursor("scan", base, pathColumn, count, visibleRows.size(), callerUid);
    if (visibleRows.size() == count && !hasAnyRewrite(rewrites) &&
        !hasAnyRewrite(relativeRewrites) && !hasAnyRewrite(bucketIdRewrites) &&
        visibleColumns == null) {
      return base;
    }
    int[] rows = new int[visibleRows.size()];
    for (int i = 0; i < rows.length; i++)
      rows[i] = visibleRows.get(i);
    return new FilteringCursor(base, rows, rewrites, pathColumn,
                               relativeRewrites, relativePathColumn,
                               bucketIdRewrites, bucketIdColumn,
                               visibleColumns);
  }

  private static int safeCount(Cursor cursor) {
    try {
      return cursor.getCount();
    } catch (Throwable ignored) {
      return -1;
    }
  }

  private static int findPathColumn(Cursor cursor) {
    String[] names = cursor.getColumnNames();
    if (names == null)
      return -1;
    int fallback = -1;
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      if ("_data".equals(name))
        return i;
      if ("data".equalsIgnoreCase(name))
        fallback = i;
    }
    return fallback;
  }

  private static int findIdColumn(Cursor cursor) {
    String[] names = cursor.getColumnNames();
    if (names == null)
      return -1;
    for (int i = 0; i < names.length; i++) {
      if ("_id".equals(names[i]))
        return i;
    }
    return -1;
  }

  private static long readId(Cursor cursor, int idColumn) {
    if (idColumn < 0)
      return -1;
    try {
      return cursor.getLong(idColumn);
    } catch (Throwable ignored) {
      return -1;
    }
  }

  private static boolean hasAnyRewrite(String[] rewrites) {
    for (String rewrite : rewrites) {
      if (rewrite != null)
        return true;
    }
    return false;
  }

  private static int findColumnIgnoreCase(Cursor cursor, String target) {
    String[] names = cursor.getColumnNames();
    if (names == null || target == null)
      return -1;
    for (int i = 0; i < names.length; i++) {
      if (target.equalsIgnoreCase(names[i]))
        return i;
    }
    return -1;
  }

  private static boolean isAnyPublicMediaRootHidden(int callerUid) {
    int userId = userIdFromUid(callerUid);
    if (userId < 0)
      return false;
    for (String root : PUBLIC_MEDIA_ROOTS) {
      String probePath = "/storage/emulated/" + userId + "/" + root +
                         "/.srx_probe";
      if (isHiddenPath(probePath, callerUid))
        return true;
    }
    return false;
  }

  private static boolean isHiddenPath(String path, int callerUid) {
    if (path == null || path.length() == 0)
      return false;
    return isHiddenRewrite(Hooker.filterPath(path, callerUid, false));
  }

  private static boolean isHiddenRewrite(String rewritten) {
    return Hooker.HIDDEN_ROW_SENTINEL.equals(rewritten) ||
        (rewritten != null && rewritten.length() == 0);
  }

  private static String relativePathProbePath(Cursor cursor,
                                              int relativePathColumn,
                                              int callerUid) {
    String relativePath = readString(cursor, relativePathColumn);
    if (relativePath == null || relativePath.length() == 0)
      return null;
    int userId = userIdFromUid(callerUid);
    if (userId < 0)
      return null;
    return "/storage/emulated/" + userId + "/" +
        normalizeRelativePath(relativePath) + "/.srx_probe";
  }

  private static String aggregateProbePath(Cursor cursor, int callerUid) {
    int bucketIdColumn = findColumnIgnoreCase(cursor, "bucket_id");
    if (bucketIdColumn < 0)
      return null;
    long bucketId = readLong(cursor, bucketIdColumn, Long.MIN_VALUE);
    if (bucketId == Long.MIN_VALUE)
      return null;
    String bucketName =
        readString(cursor, findColumnIgnoreCase(cursor, "bucket_display_name"));
    return bucketProbePath(bucketId, bucketName, callerUid);
  }

  private static String bucketProbePath(long bucketId, String bucketName,
                                        int callerUid) {
    int userId = userIdFromUid(callerUid);
    if (userId < 0)
      return null;
    String storageRoot = "/storage/emulated/" + userId;
    ArrayList<String> candidates = bucketProbeCandidates(bucketName);
    for (String relative : candidates) {
      String path = storageRoot + "/" + normalizeRelativePath(relative);
      if (path.toLowerCase(Locale.ROOT).hashCode() == (int)bucketId)
        return path + "/.srx_probe";
    }
    return null;
  }

  private static ArrayList<String> bucketProbeCandidates(String bucketName) {
    ArrayList<String> candidates = new ArrayList<>();
    String normalizedName = normalizeRelativePath(bucketName);
    for (String root : PUBLIC_MEDIA_ROOTS) {
      candidates.add(root);
      if (normalizedName.length() > 0)
        candidates.add(root + "/" + normalizedName);
    }
    return candidates;
  }

  private static String bucketIdFromStoragePath(String path, int callerUid) {
    return bucketIdFromRelativePath(relativePathFromStoragePath(path, callerUid),
                                    callerUid);
  }

  private static boolean isPrivateRedirectStoragePath(String path,
                                                      int callerUid) {
    int userId = userIdFromUid(callerUid);
    if (userId < 0 || path == null)
      return false;
    String value = path;
    if (value.startsWith("file://"))
      value = value.substring("file://".length());
    String prefix = "/storage/emulated/" + userId + "/Android/data/";
    if (!value.startsWith(prefix))
      return false;
    String relative = value.substring(prefix.length());
    return relative.equals("sdcard") || relative.startsWith("sdcard/") ||
        relative.contains("/sdcard/");
  }

  private static String bucketIdFromRelativePath(String relativePath,
                                                 int callerUid) {
    if (relativePath == null || relativePath.length() == 0)
      return null;
    int userId = userIdFromUid(callerUid);
    if (userId < 0)
      return null;
    String path = "/storage/emulated/" + userId + "/" +
        normalizeRelativePath(relativePath);
    return String.valueOf(path.toLowerCase(Locale.ROOT).hashCode());
  }

  private static String relativePathFromStoragePath(String path, int callerUid) {
    int userId = userIdFromUid(callerUid);
    if (userId < 0 || path == null)
      return null;
    String prefix = "/storage/emulated/" + userId + "/";
    String value = path;
    if (value.startsWith("file://"))
      value = value.substring("file://".length());
    if (!value.startsWith(prefix))
      return null;
    String relative = normalizeRelativePath(value.substring(prefix.length()));
    int slash = relative.lastIndexOf('/');
    if (slash >= 0)
      relative = relative.substring(0, slash);
    if (relative.length() == 0)
      return null;
    return relative + "/";
  }

  private static String normalizeRelativePath(String relativePath) {
    String value = relativePath == null ? "" : relativePath.trim();
    while (value.startsWith("/"))
      value = value.substring(1);
    while (value.endsWith("/"))
      value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String readString(Cursor cursor, int column) {
    if (column < 0)
      return null;
    try {
      return cursor.getString(column);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static long readLong(Cursor cursor, int column, long fallback) {
    if (column < 0)
      return fallback;
    try {
      return cursor.getLong(column);
    } catch (Throwable ignored) {
      return fallback;
    }
  }

  private static int userIdFromUid(int uid) {
    if (uid < ANDROID_APP_UID_START)
      return -1;
    return uid / ANDROID_USER_ID_OFFSET;
  }

  @Override
  public int getCount() {
    return rows.length;
  }

  @Override
  public String[] getColumnNames() {
    return visibleColumns != null ? visibleColumns : base.getColumnNames();
  }

  @Override
  public String getString(int column) {
    int row = currentBaseRow();
    if (column == pathColumn) {
      String rewritten = row >= 0 ? rewrites[row] : null;
      if (rewritten != null)
        return rewritten;
    }
    if (column == relativePathColumn) {
      String rewritten = row >= 0 ? relativeRewrites[row] : null;
      if (rewritten != null)
        return rewritten;
    }
    if (column == bucketIdColumn) {
      String rewritten = row >= 0 ? bucketIdRewrites[row] : null;
      if (rewritten != null)
        return rewritten;
    }
    return base.getString(column);
  }

  @Override
  public short getShort(int column) {
    return base.getShort(column);
  }

  @Override
  public int getInt(int column) {
    if (column == bucketIdColumn) {
      int row = currentBaseRow();
      String rewritten = row >= 0 ? bucketIdRewrites[row] : null;
      if (rewritten != null) {
        try {
          return Integer.parseInt(rewritten);
        } catch (Throwable ignored) {
        }
      }
    }
    return base.getInt(column);
  }

  @Override
  public long getLong(int column) {
    if (column == bucketIdColumn) {
      int row = currentBaseRow();
      String rewritten = row >= 0 ? bucketIdRewrites[row] : null;
      if (rewritten != null) {
        try {
          return Long.parseLong(rewritten);
        } catch (Throwable ignored) {
        }
      }
    }
    return base.getLong(column);
  }

  @Override
  public float getFloat(int column) {
    return base.getFloat(column);
  }

  @Override
  public double getDouble(int column) {
    return base.getDouble(column);
  }

  @Override
  public boolean isNull(int column) {
    return base.isNull(column);
  }

  @Override
  public byte[] getBlob(int column) {
    return base.getBlob(column);
  }

  @Override
  public int getType(int column) {
    return base.getType(column);
  }

  @Override
  public boolean onMove(int oldPosition, int newPosition) {
    return base.moveToPosition(rows[newPosition]);
  }

  @Override
  public void close() {
    base.close();
    super.close();
  }

  private int currentBaseRow() {
    if (mPos < 0 || mPos >= rows.length)
      return -1;
    return rows[mPos];
  }
}
