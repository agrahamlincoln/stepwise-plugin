package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

/**
 * One row of an entry's items-needed list, as listed in {@code guide.json}.
 *
 * <p>Only {@link #text} is guaranteed. It is the guide's string verbatim, curly
 * apostrophes included, because the panel renders it as written. Each of the other three
 * fields is null when the row names no single item or family, as with prose or alternatives:
 *
 * <ul>
 *   <li>{@link #itemId} is the item the row names. For a class row it is the class's first
 *       member, so a class row has a sprite too.</li>
 *   <li>{@link #itemClass} is set when the row names any of a family ("axe", "earth staff")
 *       and widens the ownership check to every member; {@link #itemId} alone would count
 *       only the first.</li>
 *   <li>{@link #quantity} is null when the guide gives no number, which is <em>not</em> the
 *       same as one. The caller decides how to treat "ropes" versus "1 rope".</li>
 * </ul>
 *
 * <p>Fields are boxed so an absent key stays null instead of collapsing to {@code 0}.
 */
@Value
@Builder
public class ItemNeeded {
    /** The guide's row, verbatim. Always present. */
    @SerializedName("text") String text;
    /** Item ID for the sprite and identity, or null when the row names no single item or family. */
    @SerializedName("itemId") Integer itemId;
    /** Item class whose every member satisfies the row, or null for an exact-item row. */
    @SerializedName("itemClass") String itemClass;
    /** Quantity the row asks for, or null when the source wrote none (unspecified, not one). */
    @SerializedName("quantity") Integer quantity;
}
