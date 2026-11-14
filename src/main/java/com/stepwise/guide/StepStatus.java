package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;

public enum StepStatus {
    @SerializedName("pending") PENDING,
    @SerializedName("active") ACTIVE,
    /**
     * A {@code background} step whose prerequisites are met but whose completion has not
     * latched. It does not hold the active-step pointer, so later steps can go ACTIVE.
     */
    @SerializedName("background") BACKGROUND,
    @SerializedName("completed") COMPLETED,
    @SerializedName("skipped") SKIPPED
}
