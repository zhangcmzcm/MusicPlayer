package org.example.music.track.service;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.track.exception.TrackRangeNotSatisfiableException;

public final class TrackStreamRangeParser {
    private static final Pattern SPEC = Pattern.compile("([0-9]*)-([0-9]*)");
    private TrackStreamRangeParser() { }

    public static AudioByteRange parse(List<String> ranges, long totalSize) {
        if (totalSize <= 0) { throw new IllegalArgumentException("Audio length must be positive"); }
        if (ranges == null || ranges.isEmpty() || ranges.size() > 1) { return null; }
        String raw = ranges.getFirst().strip();
        int equals = raw.indexOf('=');
        if (equals < 0) {
            if (raw.equalsIgnoreCase("bytes")) { throw new TrackRangeNotSatisfiableException(totalSize); }
            return null;
        }
        String unit = raw.substring(0,equals);
        if (!unit.strip().equalsIgnoreCase("bytes")) { return null; }
        String value = raw.substring(equals+1).strip();
        if (value.contains(",")) { return null; }
        var match = SPEC.matcher(value);
        if (!unit.equalsIgnoreCase("bytes") || !match.matches()
                || (match.group(1).isEmpty() && match.group(2).isEmpty())) {
            throw new TrackRangeNotSatisfiableException(totalSize);
        }
        BigInteger total = BigInteger.valueOf(totalSize);
        BigInteger last = total.subtract(BigInteger.ONE);
        if (match.group(1).isEmpty()) {
            BigInteger length = new BigInteger(match.group(2));
            if (length.signum() == 0) { throw new TrackRangeNotSatisfiableException(totalSize); }
            return new AudioByteRange(total.subtract(length.min(total)).longValueExact(),totalSize-1);
        }
        BigInteger start = new BigInteger(match.group(1));
        BigInteger end = match.group(2).isEmpty() ? last : new BigInteger(match.group(2));
        if (start.compareTo(total) >= 0 || end.compareTo(start) < 0) {
            throw new TrackRangeNotSatisfiableException(totalSize);
        }
        return new AudioByteRange(start.longValueExact(),end.min(last).longValueExact());
    }
}
