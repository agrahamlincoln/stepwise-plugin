package com.stepwise.overlay;

import com.stepwise.engine.BirdhouseTracker;
import com.stepwise.engine.BirdhouseTracker.BirdhouseSpace;
import com.stepwise.engine.BirdhouseTracker.BirdhouseTier;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Locale;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * One InfoBox for all four bird house spaces. The player runs Fossil Island once every
 * house is done, so the countdown shows the LONGEST remaining fill time. The colour goes
 * green (none ready), yellow-green (some ready), gold (all ready); the tooltip lists each
 * house. Text and colour are read live from {@link BirdhouseTracker} on every render.
 */
public class BirdhouseInfoBox extends InfoBox {

    private static final Color COLOR_FILLING = new Color(100, 160, 100);
    private static final Color COLOR_PARTIAL = new Color(180, 200, 90);
    private static final Color COLOR_READY = new Color(255, 200, 50);

    private final BirdhouseTracker tracker;

    // InfoBoxOverlay calls getTooltip() every frame for every visible box (only the display
    // is hover-gated), so the string is rebuilt at most once per second. Render calls are
    // single-threaded, so no synchronization.
    private String tooltipCache;
    private long tooltipBuiltAtSec;

    public BirdhouseInfoBox(BufferedImage image, Plugin plugin, BirdhouseTracker tracker) {
        super(image, plugin);
        this.tracker = tracker;
    }

    @Override
    public String getText() {
        long longest = longestRemaining(now());
        if (longest <= 0) {
            return "Ready";
        }
        return ceilMinutes(longest) + "m";
    }

    @Override
    public Color getTextColor() {
        long nowSec = now();
        if (longestRemaining(nowSec) <= 0) {
            return COLOR_READY;
        }
        return countReady(nowSec) > 0 ? COLOR_PARTIAL : COLOR_FILLING;
    }

    @Override
    public String getTooltip() {
        long nowSec = now();
        if (tooltipCache == null || nowSec != tooltipBuiltAtSec) {
            tooltipBuiltAtSec = nowSec;
            tooltipCache = buildTooltip(nowSec);
        }
        return tooltipCache;
    }

    private String buildTooltip(long nowSec) {
        StringBuilder sb = new StringBuilder("Bird houses");
        for (BirdhouseSpace space : BirdhouseSpace.values()) {
            if (!tracker.isSeeded(space)) {
                continue;
            }
            long remaining = tracker.remainingSeconds(space, nowSec);
            sb.append("</br>").append(space.getLabel());
            String tier = tierName(space);
            if (tier != null) {
                sb.append(" (").append(tier).append(')');
            }
            sb.append(": ").append(BirdhouseTracker.timeLeft(remaining));
        }
        return sb.toString();
    }

    @Override
    public boolean render() {
        // Hide once no house is seeded, before the plugin removes the InfoBox.
        return tracker.anySeeded();
    }

    /** Longest remaining fill time among seeded houses: when the whole run is ready. */
    private long longestRemaining(long nowSec) {
        long longest = 0;
        for (BirdhouseSpace space : BirdhouseSpace.values()) {
            if (tracker.isSeeded(space)) {
                long remaining = tracker.remainingSeconds(space, nowSec);
                if (remaining > longest) {
                    longest = remaining;
                }
            }
        }
        return longest;
    }

    private int countReady(long nowSec) {
        int count = 0;
        for (BirdhouseSpace space : BirdhouseSpace.values()) {
            if (tracker.isReady(space, nowSec)) {
                count++;
            }
        }
        return count;
    }

    /**
     * "oak" for an oak bird house, lowercase like the item name mid-line; null for a plain or
     * unknown one, which needs no tier. {@link Locale#ROOT} keeps "willow" dotted on a
     * Turkish-locale client.
     */
    private String tierName(BirdhouseSpace space) {
        BirdhouseTier tier = tracker.tierOf(space);
        if (tier == null || tier == BirdhouseTier.NORMAL) {
            return null;
        }
        return tier.name().toLowerCase(Locale.ROOT);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static long ceilMinutes(long seconds) {
        return (seconds + 59) / 60;
    }
}
