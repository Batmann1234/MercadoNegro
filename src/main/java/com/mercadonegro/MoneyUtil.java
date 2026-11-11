package com.mercadonegro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MoneyUtil {

    private static final Pattern PATTERN = Pattern.compile("^(\\d{1,15}(?:\\.\\d{1,6})?)([kmbt]?)$");

    private MoneyUtil() {}

    /** Devuelve el monto o -1 si es inválido, cero, negativo o supera {@code max}. */
    public static long parse(String raw, long max) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        Matcher m = PATTERN.matcher(s);
        if (!m.matches()) return -1;
        long mult = switch (m.group(2)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            case "t" -> 1_000_000_000_000L;
            default -> 1L;
        };
        BigDecimal v = new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(mult)).setScale(0, RoundingMode.DOWN);
        if (v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) return -1;
        return v.longValueExact();
    }

    public static String format(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    public static String time(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
