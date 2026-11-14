package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class Section {
    @SerializedName("id") String id;
    @SerializedName("title") String title;
    @SerializedName("entries") List<Entry> entries;
}
