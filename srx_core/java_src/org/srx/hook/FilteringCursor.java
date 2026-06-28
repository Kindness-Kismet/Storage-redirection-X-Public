package org.srx.hook;

import android.database.AbstractCursor;
import android.database.Cursor;
import java.util.ArrayList;
import java.util.Arrays;

final class FilteringCursor extends AbstractCursor {
  private final Cursor base;
  private final int[] rows;
  private final String[] rewrites;
  private final int pathColumn;
  private final String[] visibleColumns;

  private FilteringCursor(Cursor base, int[] rows, String[] rewrites,
                          int pathColumn, String[] visibleColumns) {
    this.base = base;
    this.rows = rows;
    this.rewrites = rewrites;
    this.pathColumn = pathColumn;
    this.visibleColumns = visibleColumns;
  }

  static Cursor wrap(Cursor base, int callerUid, String[] visibleColumns,
                     boolean preserveMissingTargets) {
    if (base == null || base.isClosed())
      return base;
    int pathColumn = findPathColumn(base);
    int idColumn = findIdColumn(base);
    int count = safeCount(base);
    Hooker.logCursor("wrap", base, pathColumn, count, count, callerUid);
    if (pathColumn < 0 && idColumn < 0)
      return base;
    if (count <= 0)
      return base;

    ArrayList<Integer> visibleRows = new ArrayList<>();
    String[] rewrites = new String[count];
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
          }
          visibleRows.add(row);
          continue;
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
    if (visibleRows.size() == count && !hasAnyRewrite(rewrites) && visibleColumns == null) {
      return base;
    }
    int[] rows = new int[visibleRows.size()];
    for (int i = 0; i < rows.length; i++)
      rows[i] = visibleRows.get(i);
    return new FilteringCursor(base, rows, rewrites, pathColumn, visibleColumns);
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
      String rewritten = rewrites[row];
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
    return base.getInt(column);
  }

  @Override
  public long getLong(int column) {
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
