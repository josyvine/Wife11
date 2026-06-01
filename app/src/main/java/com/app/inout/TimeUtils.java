package com.inout.app.utils;

import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for Date and Time formatting and calculations.
 * UPDATED: Added logic to verify if the grace period for check-in has expired.
 */
public class TimeUtils {

    private static final String TAG = "TimeUtils";

    // Format for Firestore Document IDs (e.g., "2026-01-22")
    private static final SimpleDateFormat DATE_ID_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    // Format for display and storage (e.g., "09:30 AM")
    private static final SimpleDateFormat TIME_DISPLAY_FORMAT = new SimpleDateFormat("hh:mm a", Locale.US);

    // Format for internal parsing/sorting (e.g., "2026-01-22 09:30:00")
    private static final SimpleDateFormat FULL_TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /**
     * @return Current date string (e.g., "2026-01-22") used as the Key in Firestore attendance map.
     */
    public static String getCurrentDateId() {
        return DATE_ID_FORMAT.format(new Date());
    }

    /**
     * @return Current time string for display (e.g., "09:30 AM").
     */
    public static String getCurrentTime() {
        return TIME_DISPLAY_FORMAT.format(new Date());
    }

    /**
     * Logic: Compares current system time with a target time string.
     * @param targetTime The shift start time (e.g. "09:00 AM").
     * @return true if current time is >= target time.
     */
    public static boolean isTimeReached(String targetTime) {
        if (targetTime == null || targetTime.isEmpty() || targetTime.equals("N/A")) return true;
        try {
            // Get current time formatted as HH:mm a, then parse it back to compare correctly
            String nowStr = TIME_DISPLAY_FORMAT.format(new Date());
            Date now = TIME_DISPLAY_FORMAT.parse(nowStr);
            Date target = TIME_DISPLAY_FORMAT.parse(targetTime);
            
            return now != null && (now.after(target) || now.equals(target));
        } catch (ParseException e) {
            Log.e(TAG, "isTimeReached parsing error", e);
            return true; 
        }
    }

    /**
     * Logic: Checks if the current time has passed the start time plus a grace period.
     * @param targetTime The shift start time.
     * @param graceMinutes Minutes allowed before being considered "Late".
     * @return true if current time is beyond or exactly at the grace period.
     */
    public static boolean isPastGracePeriod(String targetTime, int graceMinutes) {
        if (targetTime == null || targetTime.isEmpty() || targetTime.equals("N/A")) return false;
        try {
            String nowStr = TIME_DISPLAY_FORMAT.format(new Date());
            Date now = TIME_DISPLAY_FORMAT.parse(nowStr);
            Date target = TIME_DISPLAY_FORMAT.parse(targetTime);

            if (now != null && target != null) {
                long diffMillis = now.getTime() - target.getTime();
                long diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis);
                // Return true if we are exactly at or past the maximum allowed grace limit [3]
                return diffMinutes >= graceMinutes;
            }
        } catch (ParseException e) {
            Log.e(TAG, "isPastGracePeriod error", e);
        }
        return false;
    }

    /**
     * @return Current full timestamp for sorting.
     */
    public static long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /**
     * Calculates the duration between two time strings (e.g., "09:00 AM" and "05:00 PM").
     */
    public static String calculateDuration(String checkInTimeStr, String checkOutTimeStr) {
        if (checkInTimeStr == null || checkOutTimeStr == null) return "0h 00m";

        try {
            Date checkIn = TIME_DISPLAY_FORMAT.parse(checkInTimeStr);
            Date checkOut = TIME_DISPLAY_FORMAT.parse(checkOutTimeStr);

            if (checkIn != null && checkOut != null) {
                long diffMillis = checkOut.getTime() - checkIn.getTime();

                if (diffMillis < 0) {
                    diffMillis += TimeUnit.DAYS.toMillis(1);
                }

                long hours = TimeUnit.MILLISECONDS.toHours(diffMillis);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis) % 60;

                return String.format(Locale.US, "%dh %02dm", hours, minutes);
            }
        } catch (ParseException e) {
            Log.e(TAG, "Error calculating duration", e);
        }
        return "Error";
    }
    
    public static String formatTimestampToDate(long timestamp) {
        return DATE_ID_FORMAT.format(new Date(timestamp));
    }
}