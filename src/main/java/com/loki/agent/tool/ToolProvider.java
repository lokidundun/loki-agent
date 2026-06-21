package com.loki.agent.tool;

import java.nio.file.Path;
import java.util.List;

public interface ToolProvider {
    List<Tool> provideTools(Path workspace);
}
