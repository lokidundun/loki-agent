package com.loki.agent.prompt;

import org.springframework.stereotype.Component;

@Component
public class SystemPromptBuilder {

    public String build(String memoryContext, String selfModel,
                        String recentContext, String sessionHeader,
                        String skillsCatalog) {
        StringBuilder sb = new StringBuilder();

        // 1. Identity
        sb.append(PromptTemplates.IDENTITY);

        // 2. Behavior rules
        sb.append("\n");
        sb.append(PromptTemplates.BEHAVIOR_RULES);

        // 3. Self model (if present)
        if (selfModel != null && !selfModel.isBlank()) {
            sb.append("\n## Self Model\n");
            sb.append(selfModel);
        }

        // 4. Long-term memory (if present)
        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append("\n");
            sb.append(memoryContext);
        }

        // 5. Session context
        if (sessionHeader != null && !sessionHeader.isBlank()) {
            sb.append("\n");
            sb.append(sessionHeader);
        }

        return sb.toString();
    }
}
