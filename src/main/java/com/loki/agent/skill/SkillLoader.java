package com.loki.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private final Path skillsDir;

    public SkillLoader(Path workspace) {
        this.skillsDir = workspace.resolve("skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.error("Failed to create skills directory", e);
        }
    }

    public List<Skill> loadAll() {
        List<Skill> skills = new ArrayList<>();
        try (var stream = Files.list(skillsDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        Skill skill = parseSkill(p);
                        if (skill != null) skills.add(skill);
                    });
        } catch (IOException e) {
            log.warn("Failed to list skills directory: {}", e.getMessage());
        }
        log.debug("Loaded {} skills", skills.size());
        return skills;
    }

    public String getSkillsCatalog() {
        List<Skill> skills = loadAll();
        if (skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## Available Skills\n");
        for (Skill skill : skills) {
            sb.append("- **").append(skill.name()).append("**: ")
              .append(skill.description()).append("\n");
        }
        return sb.toString();
    }

    public String getSkillContent(String name) {
        List<Skill> skills = loadAll();
        for (Skill skill : skills) {
            if (skill.name().equals(name)) return skill.content();
        }
        return null;
    }

    private Skill parseSkill(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString().replace(".md", "");
            String description = "";
            String content = raw;

            // Parse YAML-like front-matter between --- markers
            if (raw.startsWith("---")) {
                int end = raw.indexOf("---", 3);
                if (end > 3) {
                    String frontMatter = raw.substring(3, end).trim();
                    content = raw.substring(end + 3).trim();

                    for (String line : frontMatter.split("\n")) {
                        line = line.strip();
                        if (line.startsWith("name:")) {
                            name = line.substring(5).strip();
                        } else if (line.startsWith("description:")) {
                            description = line.substring(12).strip();
                        }
                    }
                }
            }

            if (content.isBlank()) return null;
            if (description.isBlank()) description = "Skill: " + name;
            return new Skill(name, description, content);

        } catch (IOException e) {
            log.warn("Failed to read skill file {}: {}", file, e.getMessage());
            return null;
        }
    }
}
