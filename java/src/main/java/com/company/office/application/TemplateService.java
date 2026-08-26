package com.company.office.application;

import com.company.office.model.TemplateEntity;
import com.company.office.repository.TemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class TemplateService {
    private final TemplateRepository templateRepository;
    private final SidecarService sidecarService;
    private final String templatesStorageDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateService(TemplateRepository templateRepository, SidecarService sidecarService) {
        this(templateRepository, sidecarService, "app-data/templates");
    }

    public TemplateService(TemplateRepository templateRepository, SidecarService sidecarService, String templatesStorageDir) {
        this.templateRepository = templateRepository;
        this.sidecarService = sidecarService;
        this.templatesStorageDir = templatesStorageDir;
        new File(this.templatesStorageDir).mkdirs();
    }

    public CompletableFuture<TemplateEntity> importTemplate(File sourceFile, String name, String description) throws IOException {
        if (!sourceFile.exists()) {
            throw new IllegalArgumentException("Template file not found: " + sourceFile.getAbsolutePath());
        }

        String fingerprint = fingerprint(sourceFile.toPath());
        Optional<TemplateEntity> existing = listTemplates().stream()
                .filter(t -> fingerprint.equals(safeFingerprint(Path.of(t.getFilePath()))))
                .findFirst();
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());

        String tplId = "tpl-" + fingerprint.substring(0, 12);
        String filename = sourceFile.getName();
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) ext = filename.substring(dot + 1).toLowerCase();

        String tplType = ext.startsWith("xls") ? "excel" : "word";
        String targetFilename = tplId + "_" + filename;
        Path targetPath = Path.of(templatesStorageDir, targetFilename);
        Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        TemplateEntity entity = new TemplateEntity(tplId, name != null ? name : filename, tplType, targetPath.toString(), description);

        // Inspect template placeholders via Sidecar
        return sidecarService.inspectTemplateAsync(targetPath.toString())
                .thenApply(res -> {
                    try {
                        String schemaJson = objectMapper.writeValueAsString(res);
                        entity.setSchemaJson(schemaJson);
                    } catch (Exception ignored) {}
                    templateRepository.save(entity);
                    return entity;
                });
    }

    public Optional<TemplateEntity> getTemplate(String id) {
        return templateRepository.findById(id);
    }

    public List<TemplateEntity> listTemplates() {
        LinkedHashMap<String, TemplateEntity> unique = new LinkedHashMap<>();
        for (TemplateEntity template : templateRepository.findAll()) {
            Path path = Path.of(template.getFilePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) continue;
            String key = safeFingerprint(path);
            if (key == null) key = path.toString().toLowerCase(Locale.ROOT);
            TemplateEntity current = unique.get(key);
            if (current == null || prefer(template, current)) unique.put(key, template);
        }
        List<TemplateEntity> result = new ArrayList<>(unique.values());
        boolean hasVietnamCatalog = result.stream().anyMatch(t ->
                t.getFilePath().toLowerCase(Locale.ROOT).contains("vietnam"));
        if (hasVietnamCatalog) {
            result.removeIf(t -> {
                String filename = Path.of(t.getFilePath()).getFileName().toString().toLowerCase(Locale.ROOT);
                return filename.matches("(?:tpl-[a-z0-9]+_)?(?:report|invoice)_template\\.(?:docx|xlsx)");
            });
        }
        return result;
    }

    public CompletableFuture<Void> syncStorageCatalog() {
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        try (var paths = Files.walk(Path.of(templatesStorageDir))) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isOfficeTemplate)
                    .filter(p -> !p.getFileName().toString().startsWith("~$"))
                    .forEach(path -> {
                        String hash = safeFingerprint(path);
                        if (hash == null) return;
                        String ext = extension(path.getFileName().toString());
                        String type = ext.startsWith("xls") ? "excel" : "word";
                        Optional<TemplateEntity> existing = templateRepository.findAll().stream()
                                .filter(t -> hash.equals(safeFingerprint(Path.of(t.getFilePath())))).findFirst();
                        TemplateEntity entity = existing.orElseGet(() -> new TemplateEntity(
                                "tpl-auto-" + hash.substring(0, 12), displayName(path), type,
                                path.toAbsolutePath().normalize().toString(),
                                "Tự động phát hiện; loại: " + inferCategory(path.getFileName().toString())));
                        if (entity.getSchemaJson() != null
                                && entity.getSchemaJson().contains("\"template_fingerprint\":\"" + hash + "\"")) return;
                        tasks.add(sidecarService.inspectTemplateAsync(entity.getFilePath()).thenAccept(schema -> {
                            try { entity.setSchemaJson(objectMapper.writeValueAsString(schema)); }
                            catch (Exception ignored) { entity.setSchemaJson("{}"); }
                            templateRepository.save(entity);
                        }));
                    });
        } catch (IOException ignored) {}
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }

    public List<TemplateEntity> listTemplatesForDocument(String documentType, String rawText) {
        String context = ((documentType == null ? "" : documentType) + " " +
                (rawText == null ? "" : rawText)).toLowerCase(Locale.ROOT);
        if ((context.contains("cộng hòa xã hội chủ nghĩa việt nam") || context.contains("cong hoa xa hoi chu nghia viet nam"))
                && (context.contains("kính gửi") || context.contains("kinh gui"))
                && (context.contains("v/v") || context.contains("v / v"))) {
            context += " official công văn cong van";
        }
        final String contextText = context;
        List<TemplateEntity> result = new ArrayList<>(listTemplates());
        result.sort(Comparator.comparingInt((TemplateEntity t) -> compatibilityScore(t, contextText)).reversed()
                .thenComparing(TemplateEntity::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private int compatibilityScore(TemplateEntity template, String context) {
        String haystack = (template.getName() + " " + template.getDescription() + " " + template.getFilePath())
                .toLowerCase(Locale.ROOT);
        int score = 0;
        String[][] groups = {{"invoice", "hóa đơn", "hoa don", "gtgt"}, {"receipt", "phiếu thu", "phieu thu"},
                {"report", "báo cáo", "bao cao"}, {"decision", "quyết định", "quyet dinh"},
                {"minutes", "biên bản", "bien ban"}, {"proposal", "tờ trình", "to trinh"},
                {"official", "công văn", "cong van"}, {"contract", "hợp đồng", "hop dong"}};
        for (String[] group : groups) {
            boolean docMatch = false, templateMatch = false;
            for (String keyword : group) {
                docMatch |= context.contains(keyword);
                templateMatch |= haystack.contains(keyword);
            }
            if (docMatch && templateMatch) score += 100;
            if ("official".equals(group[0]) && docMatch && templateMatch
                    && context.contains("official công văn")) score += 200;
        }
        return score;
    }

    private boolean isOfficeTemplate(Path path) {
        return List.of("docx", "doc", "xlsx", "xlsm").contains(extension(path.getFileName().toString()));
    }

    private String displayName(Path path) {
        String stem = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String cleaned = stem.replaceFirst("^tpl-[a-zA-Z0-9]+_", "").replace('_', ' ').trim();
        return cleaned.isEmpty() ? path.getFileName().toString()
                : Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private String inferCategory(String filename) {
        String value = filename.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (value.contains("hoa don") || value.contains("invoice")) return "hóa đơn";
        if (value.contains("phieu thu")) return "phiếu thu";
        if (value.contains("bao cao") || value.contains("report")) return "báo cáo";
        if (value.contains("quyet dinh")) return "quyết định";
        if (value.contains("bien ban")) return "biên bản";
        if (value.contains("to trinh")) return "tờ trình";
        if (value.contains("cong van")) return "công văn";
        return "tổng quát";
    }

    private boolean prefer(TemplateEntity candidate, TemplateEntity current) {
        return candidate.getFilePath().toLowerCase(Locale.ROOT).contains("vietnam")
                && !current.getFilePath().toLowerCase(Locale.ROOT).contains("vietnam");
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeFingerprint(Path path) {
        try { return fingerprint(path); } catch (Exception ignored) { return null; }
    }

    private String fingerprint(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateTemplate(TemplateEntity template) {
        templateRepository.save(template);
    }

    public void deleteTemplate(String id) {
        Optional<TemplateEntity> opt = templateRepository.findById(id);
        if (opt.isPresent()) {
            try {
                Files.deleteIfExists(Path.of(opt.get().getFilePath()));
            } catch (Exception ignored) {}
            templateRepository.delete(id);
        }
    }
}
