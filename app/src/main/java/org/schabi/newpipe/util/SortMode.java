package org.schabi.newpipe.util;

/**
 * Display order of the streams of a local playlist. Only changes how the list is shown,
 * the stored order of the playlist is never touched by it.
 */
public enum SortMode {
    ORIGIN,
    ORIGIN_REVERSE,
    SORT_NAME,
    SORT_NAME_REVERSE,
    DURATION,
    DURATION_REVERSE;

    public static SortMode parse(final String name) {
        try {
            return name == null ? ORIGIN : valueOf(name);
        } catch (final IllegalArgumentException e) {
            return ORIGIN;
        }
    }

    public boolean isOriginal() {
        return this == ORIGIN;
    }
}
