package com.loki.agent.config;

import com.loki.agent.tool.ToolRegistry;
import com.loki.agent.tool.tools.EditFileTool;
import com.loki.agent.tool.tools.ListDirTool;
import com.loki.agent.tool.tools.ReadFileTool;
import com.loki.agent.tool.tools.WriteFileTool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(AgentConfig.class)
public class AppConfig {

    @Bean
    public Path workspacePath(AgentConfig config) {
        Path workspace = Path.of(config.workspace()).toAbsolutePath().normalize();
        workspace.toFile().mkdirs();
        return workspace;
    }

    @Bean
    public ToolRegistry toolRegistry(Path workspace, AgentConfig config) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace));
        registry.register(new WriteFileTool(workspace));
        registry.register(new EditFileTool(workspace));
        registry.register(new ListDirTool(workspace));
        return registry;
    }
}
