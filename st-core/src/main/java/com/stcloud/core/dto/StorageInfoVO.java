package com.stcloud.core.dto;

import lombok.Data;

@Data
public class StorageInfoVO {
    private Long used;
    private Long quota;

    public double getPercentage() {
        if (quota == null || quota == 0) {
            return 0;
        }
        return Math.round((double) used / quota * 10000) / 100.0;
    }
}
