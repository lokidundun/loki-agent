package com.loki.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

@Component
public class SpiToolLoader {

    private static final Logger log = LoggerFactory.getLogger(SpiToolLoader.class);

    public List<Tool> loadAll(Path workspace) {
        List<Tool> tools = new ArrayList<>();
        ServiceLoader<ToolProvider> loader = ServiceLoader.load(ToolProvider.class);
        for (ToolProvider provider : loader) {
            try {
                List<Tool> provided = provider.provideTools(workspace);
                tools.addAll(provided);
                log.info("SPI loaded {} tools from {}", provided.size(), provider.getClass().getName());
            } catch (Exception e) {
                log.warn("SPI ToolProvider {} failed: {}", provider.getClass().getName(), e.getMessage());
            }
        }
        if (tools.isEmpty()) {
            log.debug("No SPI tools found");
        }
        return tools;
    }
}
