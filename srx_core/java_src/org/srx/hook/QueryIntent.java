package org.srx.hook;

import android.net.Uri;
import android.os.Bundle;

final class QueryIntent {
  static final QueryIntent DEFAULT = new QueryIntent(false);
  static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";
  static final String QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args";

  final boolean preserveMissingTargets;

  private QueryIntent(boolean preserveMissingTargets) {
    this.preserveMissingTargets = preserveMissingTargets;
  }

  static QueryIntent from(Object[] args) {
    if (isExactPathLookup(args) || isExactRelativeNameLookup(args) || isItemUri(args)) {
      return new QueryIntent(true);
    }
    return DEFAULT;
  }

  private static boolean isItemUri(Object[] args) {
    Uri uri = queryUri(args);
    if (uri == null)
      return false;
    String last = uri.getLastPathSegment();
    return last != null && last.length() > 0 && isDigitsOnly(last);
  }

  private static boolean isExactPathLookup(Object[] args) {
    QueryArgs queryArgs = QueryArgs.from(args);
    if (queryArgs.selection == null || queryArgs.selectionArgs == null)
      return false;
    String normalized = normalizeSelection(queryArgs.selection);
    if (!hasExactColumnPredicate(normalized, "_data") &&
        !hasExactColumnPredicate(normalized, "data")) {
      return false;
    }
    return hasStoragePathArg(queryArgs.selectionArgs);
  }

  private static boolean isExactRelativeNameLookup(Object[] args) {
    QueryArgs queryArgs = QueryArgs.from(args);
    if (queryArgs.selection == null || queryArgs.selectionArgs == null)
      return false;
    String normalized = normalizeSelection(queryArgs.selection);
    boolean hasRelativePath = hasExactColumnPredicate(normalized, "relative_path");
    boolean hasDisplayName = hasExactColumnPredicate(normalized, "_display_name") ||
        hasExactColumnPredicate(normalized, "display_name");
    return hasRelativePath && hasDisplayName;
  }

  private static Uri queryUri(Object[] args) {
    if (args == null || args.length == 0 || !(args[0] instanceof Uri))
      return null;
    return (Uri)args[0];
  }

  private static boolean hasStoragePathArg(String[] args) {
    for (String value : args) {
      if (value != null && value.startsWith("/storage/"))
        return true;
    }
    return false;
  }

  private static boolean hasExactColumnPredicate(String selection, String column) {
    String token = column.toLowerCase() + " = ?";
    return selection.contains(token) || selection.contains("(" + token + ")");
  }

  private static String normalizeSelection(String selection) {
    return selection == null ? "" : selection.toLowerCase().replaceAll("\\s+", " ").trim();
  }

  private static boolean isDigitsOnly(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i)))
        return false;
    }
    return true;
  }

  private static final class QueryArgs {
    final String selection;
    final String[] selectionArgs;

    private QueryArgs(String selection, String[] selectionArgs) {
      this.selection = selection;
      this.selectionArgs = selectionArgs;
    }

    static QueryArgs from(Object[] args) {
      String selection = null;
      String[] selectionArgs = null;
      if (args != null) {
        for (Object arg : args) {
          if (arg instanceof Bundle) {
            Bundle bundle = (Bundle)arg;
            selection = bundle.getString(QUERY_ARG_SQL_SELECTION);
            selectionArgs = bundle.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);
          }
        }
        if (args.length >= 4) {
          if (selection == null && args[2] instanceof String)
            selection = (String)args[2];
          if (selectionArgs == null && args[3] instanceof String[])
            selectionArgs = (String[])args[3];
        }
      }
      return new QueryArgs(selection, selectionArgs);
    }
  }
}
