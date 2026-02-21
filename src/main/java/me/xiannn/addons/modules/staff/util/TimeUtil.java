package me.xiannn.addons.modules.staff.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtil {

    // Parses strings like "1d12h30m" into milliseconds
    public static long parseDuration(String input) {
        if (input == null || input.isEmpty()) return 0;
        
        long totalMillis = 0;
        Pattern pattern = Pattern.compile("(\\d+)([wdhms])");
        Matcher matcher = pattern.matcher(input.toLowerCase());

        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            
            switch (unit) {
                case "w" -> totalMillis += value * 604800000L;
                case "d" -> totalMillis += value * 86400000L;
                case "h" -> totalMillis += value * 3600000L;
                case "m" -> totalMillis += value * 60000L;
                case "s" -> totalMillis += value * 1000L;
            }
        }
        return totalMillis;
    }

    public static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
}
