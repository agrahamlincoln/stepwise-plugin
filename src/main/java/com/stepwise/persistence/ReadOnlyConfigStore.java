package com.stepwise.persistence;

import java.util.function.BiFunction;

/**
 * A {@link ConfigStore} that drops every write, so code handed one can read an account's
 * saved state and cannot change it however it is written.
 */
abstract class ReadOnlyConfigStore implements ConfigStore {

    /** Reads one key of a named RS profile, as {@code ConfigManager}'s three-argument getter. */
    @FunctionalInterface
    interface ProfileReads {
        String get(String group, String profileKey, String key);
    }

    /**
     * RS-profile reads from {@code profileKey} through {@code profileReads}, global reads
     * through {@code globalReads}.
     */
    static ConfigStore over(ProfileReads profileReads, BiFunction<String, String, String> globalReads,
            String profileKey) {
        return new ReadOnlyConfigStore() {
            @Override
            public String getRSProfileConfiguration(String group, String key) {
                return profileReads.get(group, profileKey, key);
            }

            @Override
            public String getConfiguration(String group, String key) {
                return globalReads.apply(group, key);
            }

            @Override
            public String rsProfileKey() {
                return profileKey;
            }
        };
    }

    @Override
    public final boolean isReadOnly() {
        return true;
    }

    @Override
    public final void setRSProfileConfiguration(String group, String key, String value) {
        // Dropped.
    }

    @Override
    public final void unsetRSProfileConfiguration(String group, String key) {
        // Dropped.
    }

    @Override
    public final void setConfiguration(String group, String key, String value) {
        // Dropped.
    }

    @Override
    public final void unsetConfiguration(String group, String key) {
        // Dropped.
    }
}
