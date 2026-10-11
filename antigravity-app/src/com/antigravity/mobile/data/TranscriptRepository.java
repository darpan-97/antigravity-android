package com.antigravity.mobile.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.antigravity.mobile.model.MessageItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TranscriptRepository {
    private final TranscriptDatabaseHelper mDbHelper;

    public interface OnDataChangeListener {
        void onDataChanged(List<MessageItem> messages);
    }

    private final List<OnDataChangeListener> mListeners = new ArrayList<>();

    public TranscriptRepository(Context context) {
        mDbHelper = new TranscriptDatabaseHelper(context.getApplicationContext());
    }

    public synchronized void addListener(OnDataChangeListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public synchronized void removeListener(OnDataChangeListener listener) {
        mListeners.remove(listener);
    }

    private synchronized void notifyListeners() {
        List<MessageItem> list = getAllMessages();
        for (OnDataChangeListener l : mListeners) {
            l.onDataChanged(list);
        }
    }

    public synchronized List<MessageItem> getAllMessages() {
        List<MessageItem> result = new ArrayList<>();
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                TranscriptDatabaseHelper.TABLE_MESSAGES,
                null, null, null, null, null,
                TranscriptDatabaseHelper.COL_TIMESTAMP + " ASC"
        );

        if (cursor != null) {
            try {
                int colId = cursor.getColumnIndexOrThrow(TranscriptDatabaseHelper.COL_ID);
                int colSender = cursor.getColumnIndexOrThrow(TranscriptDatabaseHelper.COL_SENDER);
                int colContent = cursor.getColumnIndexOrThrow(TranscriptDatabaseHelper.COL_CONTENT);
                int colTime = cursor.getColumnIndexOrThrow(TranscriptDatabaseHelper.COL_TIMESTAMP);
                int colStream = cursor.getColumnIndexOrThrow(TranscriptDatabaseHelper.COL_IS_STREAMING);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(colId);
                    String sender = cursor.getString(colSender);
                    String content = cursor.getString(colContent);
                    long time = cursor.getLong(colTime);
                    boolean isStreaming = cursor.getInt(colStream) == 1;

                    result.add(new MessageItem(id, sender, content, time, isStreaming));
                }
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    public synchronized void insertOrUpdateMessage(MessageItem item) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(TranscriptDatabaseHelper.COL_ID, item.getId());
        cv.put(TranscriptDatabaseHelper.COL_SENDER, item.getSender());
        cv.put(TranscriptDatabaseHelper.COL_CONTENT, item.getContent());
        cv.put(TranscriptDatabaseHelper.COL_TIMESTAMP, item.getTimestamp());
        cv.put(TranscriptDatabaseHelper.COL_IS_STREAMING, item.isStreaming() ? 1 : 0);

        db.insertWithOnConflict(
                TranscriptDatabaseHelper.TABLE_MESSAGES,
                null, cv,
                SQLiteDatabase.CONFLICT_REPLACE
        );

        notifyListeners();
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.delete(TranscriptDatabaseHelper.TABLE_MESSAGES, null, null);
        notifyListeners();
    }

    public synchronized void replaceAll(List<MessageItem> items) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TranscriptDatabaseHelper.TABLE_MESSAGES, null, null);
            for (MessageItem item : items) {
                ContentValues cv = new ContentValues();
                cv.put(TranscriptDatabaseHelper.COL_ID, item.getId());
                cv.put(TranscriptDatabaseHelper.COL_SENDER, item.getSender());
                cv.put(TranscriptDatabaseHelper.COL_CONTENT, item.getContent());
                cv.put(TranscriptDatabaseHelper.COL_TIMESTAMP, item.getTimestamp());
                cv.put(TranscriptDatabaseHelper.COL_IS_STREAMING, 0);
                db.insertWithOnConflict(TranscriptDatabaseHelper.TABLE_MESSAGES, null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        notifyListeners();
    }
}
