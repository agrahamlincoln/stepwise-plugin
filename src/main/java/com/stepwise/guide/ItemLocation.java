package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;

public enum ItemLocation {
    @SerializedName("inventory") INVENTORY,
    @SerializedName("bank") BANK,
    @SerializedName("anywhere") ANYWHERE
}
