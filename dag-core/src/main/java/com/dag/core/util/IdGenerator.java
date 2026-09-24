package com.dag.core.util;

import java.util.UUID;

/**
 * 引擎 id 生成器（VARCHAR(64)，UUID 去横线）。
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
