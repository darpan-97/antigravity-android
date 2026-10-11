package com.antigravity.mobile.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class TranscriptDatabaseHelper extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "antigravity_transcript.db";
    public static final int DATABASE_VERSION = 1;

    public static final String TABLE_MESSAGES = "messages";
    public static final String COL_ID = "id";
    public static final String COL_SENDER = "sender";
    public static final String COL_CONTENT = "content";
    public static final String COL_TIMESTAMP = "timestamp";
    public static final String COL_IS_STREAMING = "is_streaming";

    private static final String SQL_CREATE_TABLE =
            "CREATE TABLE " + TABLE_MESSAGES + " (" +
            COL_ID + " INTEGER PRIMARY KEY, " +
            COL_SENDER + " TEXT NOT NULL, " +
            COL_CONTENT + " TEXT NOT NULL, " +
            COL_TIMESTAMP + " INTEGER NOT NULL, " +
            COL_IS_STREAMING + " INTEGER NOT NULL DEFAULT 0);";

    public TranscriptDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        onCreate(db);
    }
}
