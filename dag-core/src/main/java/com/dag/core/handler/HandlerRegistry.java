package com.dag.core.handler;

import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * handler 注册表：从 Spring 容器收集全部 NodeHandler Bean，key = Bean 名（@Component 名）。
 * <p>匹配规则（已拍板）：大小写敏感精确匹配。由 DagAutoConfiguration 注册为 Bean 并调用 init()。</p>
 */
public class HandlerRegistry {

    private final ApplicationContext applicationContext;

    /** Bean 名 → NodeHandler */
    private final Map<String, NodeHandler> registry = new LinkedHashMap<>();

    public HandlerRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** 收集容器中全部 NodeHandler（必须在 Spring 容器就绪后调用） */
    public void init() {
        Map<String, NodeHandler> beans = applicationContext.getBeansOfType(NodeHandler.class);
        for (Map.Entry<String, NodeHandler> entry : beans.entrySet()) {
            registry.put(entry.getKey(), entry.getValue());
        }
    }

    public NodeHandler get(String beanName) {
        return registry.get(beanName);
    }

    public boolean contains(String beanName) {
        return registry.containsKey(beanName);
    }

    public Map<String, NodeHandler> all() {
        return Collections.unmodifiableMap(registry);
    }
}
