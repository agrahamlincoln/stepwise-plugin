package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Value;

import java.util.List;

/** Top-level structure of {@code milestones.json}. */
@Value
public class MilestonesFile {
    @SerializedName("milestones") List<Milestone> milestones;
}
