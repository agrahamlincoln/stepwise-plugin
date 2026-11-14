package com.stepwise.persistence;

import net.runelite.client.config.ConfigManager;

/**
 * The slice of {@link ConfigManager} that {@link StepwisePersistence} reads and writes.
 *
 * <p>Stepwise persists only pre-encoded strings, so this needs none of ConfigManager's typed
 * overloads.
 */
public interface ConfigStore {

    String getRSProfileConfiguration(String group, String key);

    void setRSProfileConfiguration(String group, String key, String value);

    void unsetRSProfileConfiguration(String group, String key);

    String getConfiguration(String group, String key);

    void setConfiguration(String group, String key, String value);

    void unsetConfiguration(String group, String key);

    /**
     * The key of the RS profile that the RS-profile methods read and write, or null when
     * there is none. RuneLite has no RS profile until an account logs in.
     */
    String rsProfileKey();

    /** Whether every write is dropped, as in {@link #readOnly}'s stores. */
    default boolean isReadOnly() {
        return false;
    }

    /** The live client's store. */
    static ConfigStore of(ConfigManager configManager) {
        return new ConfigStore() {
            @Override
            public String getRSProfileConfiguration(String group, String key) {
                return configManager.getRSProfileConfiguration(group, key);
            }

            @Override
            public void setRSProfileConfiguration(String group, String key, String value) {
                configManager.setRSProfileConfiguration(group, key, value);
            }

            @Override
            public void unsetRSProfileConfiguration(String group, String key) {
                configManager.unsetRSProfileConfiguration(group, key);
            }

            @Override
            public String getConfiguration(String group, String key) {
                return configManager.getConfiguration(group, key);
            }

            @Override
            public void setConfiguration(String group, String key, String value) {
                configManager.setConfiguration(group, key, value);
            }

            @Override
            public void unsetConfiguration(String group, String key) {
                configManager.unsetConfiguration(group, key);
            }

            @Override
            public String rsProfileKey() {
                return configManager.getRSProfileKey();
            }
        };
    }

    /**
     * A named RS profile's keys, readable while no account is logged in, with every write
     * dropped.
     *
     * <p>RS-profile reads go to {@code profileKey} rather than the current profile, which is
     * null at the login screen. Global reads pass through.
     */
    static ConfigStore readOnly(ConfigManager configManager, String profileKey) {
        return ReadOnlyConfigStore.over(configManager::getConfiguration,
                configManager::getConfiguration, profileKey);
    }
}
