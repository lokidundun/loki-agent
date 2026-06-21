package com.loki.agent.config;

import com.loki.agent.a2a.A2AServer;
import com.loki.agent.agent.SubAgentManager;
import com.loki.agent.event.EventBus;
import com.loki.agent.llm.LlmProvider;
import com.loki.agent.mcp.McpManager;
import com.loki.agent.memory.MemoryStore;
import com.loki.agent.tool.ToolHook;
import com.loki.agent.tool.ToolRegistry;
import com.loki.agent.tool.SpiToolLoader;
import com.loki.agent.tool.tools.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties(AgentConfig.class)
public class AppConfig implements InitializingBean {

    private final Optional<McpManager> mcpManager;
    private final Optional<A2AServer> a2aServer;

    public AppConfig(Optional<McpManager> mcpManager, Optional<A2AServer> a2aServer) {
        this.mcpManager = mcpManager;
        this.a2aServer = a2aServer;
    }

    @Override
    public void afterPropertiesSet() {
        a2aServer.ifPresent(A2AServer::start);
    }

    @Bean
    public Path workspacePath(AgentConfig config) {
        Path workspace = Path.of(config.workspace()).toAbsolutePath().normalize();
        workspace.toFile().mkdirs();
        return workspace;
    }

    @Bean
    public DataSource dataSource(AgentConfig config) {
        String dbPath = Path.of(config.workspace()).getParent()
                .resolve("loki-agent.db").toAbsolutePath().toString();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    @Bean
    public ToolRegistry toolRegistry(Path workspace, MemoryStore memoryStore,
                                      LlmProvider llmProvider, SpiToolLoader spiToolLoader,
                                      List<ToolHook> hooks, EventBus eventBus,
                                      SubAgentManager subAgentManager) {
        ToolRegistry registry = new ToolRegistry();

        // Built-in tools
        registry.register(new ReadFileTool(workspace));
        registry.register(new WriteFileTool(workspace));
        registry.register(new EditFileTool(workspace));
        registry.register(new ListDirTool(workspace));
        registry.register(new MemoryTool(memoryStore, llmProvider));
        registry.register(new DelegateTool(subAgentManager));

        // MCP tools
        mcpManager.ifPresent(mgr -> {
            // MCP servers are configured via application.yml
            // They would be started here with config from AgentConfig
        });

        // SPI-loaded tools
        spiToolLoader.loadAll(workspace).forEach(registry::register);

        // Hooks and EventBus
        registry.setHooks(hooks);
        registry.setEventBus(eventBus);

        return registry;
    }
}
