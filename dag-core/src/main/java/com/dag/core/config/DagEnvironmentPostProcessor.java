package com.dag.core.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

/**
 * starter 环境后置处理：业务方未配置 mybatis.mapper-locations 时，注入 dag-core 默认值，
 * 使 dag-core 内 mapper XML 开箱即用（classpath*:mapper/*.xml，跨 jar 匹配）。
 */
public class DagEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DEFAULT_SOURCE_NAME = "dagDefaultMybatisMapperLocations";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty("mybatis.mapper-locations") != null) {
            return; // 业务方已显式配置，尊重之
        }
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("mybatis.mapper-locations", "classpath*:mapper/*.xml");
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource(DEFAULT_SOURCE_NAME, defaults));
    }
}
