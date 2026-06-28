package org.srx.hook;

import java.util.Arrays;

final class ProjectionPatch {
  static final ProjectionPatch NONE = new ProjectionPatch(null);
  final String[] visibleColumns;

  private ProjectionPatch(String[] visibleColumns) {
    this.visibleColumns = visibleColumns;
  }

  static ProjectionPatch apply(Object[] rawArgs, Object[] actualArgs) {
    try {
      if (actualArgs == null || actualArgs.length < 2 ||
          !(actualArgs[1] instanceof String[]))
        return NONE;
      String[] projection = (String[])actualArgs[1];
      if (projection == null || projection.length == 0 ||
          hasPathColumn(projection) || !hasIdColumn(projection)) {
        return NONE;
      }
      String[] patched = Arrays.copyOf(projection, projection.length + 1);
      patched[projection.length] = "_data";
      actualArgs[1] = patched;
      if (rawArgs != null && rawArgs.length == 1 && rawArgs[0] instanceof Object[]) {
        ((Object[])rawArgs[0])[1] = patched;
      } else if (rawArgs != null && rawArgs.length > 2) {
        rawArgs[2] = patched;
      }
      return new ProjectionPatch(projection);
    } catch (Throwable ignored) {
      return NONE;
    }
  }

  private static boolean hasPathColumn(String[] columns) {
    for (String column : columns) {
      if ("_data".equals(column) || "data".equalsIgnoreCase(column))
        return true;
    }
    return false;
  }

  private static boolean hasIdColumn(String[] columns) {
    for (String column : columns) {
      if ("_id".equals(column))
        return true;
    }
    return false;
  }
}
