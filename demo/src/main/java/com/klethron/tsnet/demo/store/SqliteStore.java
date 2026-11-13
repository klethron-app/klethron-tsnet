package com.klethron.tsnet.demo.store;

import android.database.sqlite.SQLiteDatabase;
import com.klethron.tsnet.StateStore;
import java.io.File;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Demo-owned persistence; the library has no SQLite or filesystem dependency.
@NullMarked
public final class SqliteStore implements StateStore, AutoCloseable {
    private final SQLiteDatabase database;

    public SqliteStore(File file) {
        database = SQLiteDatabase.openOrCreateDatabase(file, null);
        database.execSQL("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value BLOB NOT NULL) WITHOUT ROWID");
    }

    @Override
    public byte @Nullable [] read(String key) {
        try (var cursor = database.rawQuery("SELECT value FROM state WHERE key = ?", new String[] {key})) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
        }
    }

    @Override
    public void write(String key, byte @Nullable [] value) {
        if (value == null) database.execSQL("DELETE FROM state WHERE key = ?", new Object[] {key});
        else database.execSQL("INSERT OR REPLACE INTO state VALUES (?, ?)", new Object[] {key, value});
    }

    @Override
    public void close() { database.close(); }
}
