/*
 * Copyright 2016 Adrian Siekierka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pl.asie.modalyze;

import org.apache.commons.codec.digest.DigestUtils;
import org.objectweb.asm.*;
import pl.asie.modalyze.mcp.MCPDataManager;
import pl.asie.modalyze.mcp.MCPUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public class ModAnalyzer {
    public static final MCPDataManager MCP = new MCPDataManager();
    private static final List<String> FORGE_MOD_ANNOTATIONS = Arrays.asList(
            "Lfml/Mod;", // very early 1.2.5 commits
            "Lcpw/mods/fml/common/Mod;", // 1.2.5-1.7.10
            "Lnet/minecraftforge/fml/common/Mod;" // 1.8+
    );
    private final Set<String> keys = new HashSet<>();
    private final File file;
    private boolean versionHeuristics, generateHash, storeFilenames, isVerbose;

    public class ModHMethodVisitor extends MethodVisitor {
        public ModHMethodVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String desc, boolean itf) {
            keys.add(MCPUtils.getMethodKey(owner + "/" + name, desc));
        }
    }

    public class ModAnnotationVisitor extends AnnotationVisitor {
        private final ModMetadata metadata;
        private Map<String, Object> data = new HashMap<>();

        public ModAnnotationVisitor(ModMetadata metadata, AnnotationVisitor av) {
            super(Opcodes.ASM5, av);
            this.metadata = metadata;
        }

        @Override
        public void visit(String name, Object value) {
            super.visit(name, value);
            data.put(name, value);
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            super.visitEnum(name, desc, value);
            data.put(name, value);
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            metadata.valid = true;

            if (data.containsKey("modid")) {
                metadata.modid = (String) data.get("modid"); // always more accurate
                metadata.provides = StringUtils.append(metadata.provides, (String) data.get("modid"));
            }

            if (data.containsKey("version")) {
                metadata.version = StringUtils.select(metadata.version, (String) data.get("version"));
            }

            String dependencyStr = data.containsKey("dependencies") ? ((String) data.get("dependencies"))
                    : (data.containsKey("dependsOn") ? ((String) data.get("dependsOn")) : null);

            if (dependencyStr != null) {
                List<String> dependencies = Arrays.asList(dependencyStr.split(";"));
                for (String s : dependencies) {
                    String[] dep = s.split(":");
                    if (dep.length == 2 && dep[0].startsWith("require")) {
                        // ModLoader used "require-" instead of "required-"
                        metadata.dependencies = addDependency(metadata.dependencies, dep[1]);
                    }
                }
            }

            if (data.containsKey("acceptedMinecraftVersions")
                    && ModAnalyzerUtils.isValidMcVersion((String) data.get("acceptedMinecraftVersions"))) {
                metadata.dependencies = addDependency(metadata.dependencies, "minecraft@" + data.get("acceptedMinecraftVersions"));
            }

            if (data.containsKey("clientSideOnly")) {
                if (((boolean) data.get("clientSideOnly")) == true) {
                    metadata.side = "client";
                }
            } else if (data.containsKey("serverSideOnly")) {
                if (((boolean) data.get("serverSideOnly")) == true) {
                    metadata.side = "server";
                }
            }
        }
    }

    public class ModClassVisitor extends ClassVisitor {
        private final ModMetadata metadata;
        private String superName, className;
        private boolean isBaseMod, useClassNameAsModName;

        public ModClassVisitor(ModMetadata metadata) {
            super(Opcodes.ASM5);
            this.metadata = metadata;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);

            this.superName = superName;
            this.className = name;
            if (superName.endsWith("BaseMod") || superName.endsWith("BaseModMp") || superName.equals("forge/NetworkMod")) {
                isBaseMod = true;
                useClassNameAsModName = true;
                metadata.valid = true;
            }
        }

        @Override
        public void visitEnd() {
            if (useClassNameAsModName) {
                String[] data = className.split("/");
                metadata.modid = metadata.name = StringUtils.select(metadata.name, data[data.length - 1]);
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            if (useClassNameAsModName && name.equals("getName")) {
                // getName() is overridden so it is not reliable
                useClassNameAsModName = false;
            }

            MethodVisitor visitor;
            if (versionHeuristics) {
                visitor = new ModHMethodVisitor();
            } else {
                visitor = super.visitMethod(access, name, desc, signature, exceptions);
            }
            return visitor;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            AnnotationVisitor visitor = super.visitAnnotation(desc, visible);

            if (FORGE_MOD_ANNOTATIONS.contains(desc)) {
                return new ModAnnotationVisitor(metadata, visitor);
            } else {
                return visitor;
            }
        }
    }

    public ModAnalyzer(File file) {
        this.file = file;
    }

    public ModAnalyzer setGenerateHash(boolean gh) {
        generateHash = gh;
        return this;
    }

    public ModAnalyzer setVersionHeuristics(boolean v) {
        versionHeuristics = v;
        return this;
    }

    public ModAnalyzer setStoreFilenames(boolean sf) {
        storeFilenames = sf;
        return this;
    }

    public ModAnalyzer setIsVerbose(boolean iv) {
        isVerbose = iv;
        return this;
    }

    private Map<String, String> addDependency(Map<String, String> deps, String dep) {
        if (deps == null) {
            deps = new HashMap<>();
        }

        String modName = dep;
        String modVersion = "*";
        if (dep.contains("@")) {
            modName = dep.split("@")[0];
            if (dep.split("@").length == 2) {
                modVersion = dep.split("@")[1];
            }
        }

        modName = modName.trim();
        modVersion = modVersion.trim();

        if (deps.containsKey(modName)) {
            if (!"*".equals(modVersion) && "*".equals(deps.get(modName))) {
                deps.put(modName, modVersion);
            }
        } else {
            deps.put(modName, modVersion);
        }

        return deps;
    }

    private ModMetadata getOrCreate(Map<String, ModMetadata> metaMap, String id) {
        ModMetadata metadata = metaMap.get(id);
        if (metadata == null) {
            metadata = new ModMetadata();
            metadata.modid = id;
            metaMap.put(metadata.modid, metadata);
        }
        return metadata;
    }

    private void appendManifest(ModMetadata metadata, InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("FMLCorePlugin:")) {
                metadata.hasCoremod = true;
            }
        }
    }
    private void appendMcmodInfo(ModMetadata metadata, InputStream stream) throws IOException {
        McmodInfo info = McmodInfo.get(stream);
        if (info != null && info.modList != null) {
            for (McmodInfo.Entry entry : info.modList) {
                if (entry.modid == null || "examplemod".equals(entry.modid) /* You have no idea how many mods do this */) {
                    continue;
                }

                if (metadata.modid == null) {
                    metadata.modid = entry.modid;
                } else if (!metadata.modid.equals(entry.modid)) {
                    continue;
                }

                metadata.valid = true;
                metadata.provides = StringUtils.append(metadata.provides, entry.modid);
                metadata.name = StringUtils.selectLonger(entry.name, metadata.name);
                metadata.description = StringUtils.select(entry.description, metadata.description);
                metadata.version = StringUtils.select(entry.version, metadata.version);
                metadata.homepage = StringUtils.select(entry.url, metadata.homepage);
                if (entry.mcversion != null && ModAnalyzerUtils.isValidMcVersion(entry.mcversion)) {
                    metadata.dependencies = addDependency(metadata.dependencies, "minecraft@" + entry.mcversion);
                }
                if (entry.authorList != null) {
                    metadata.authors = StringUtils.append(metadata.authors, entry.authorList);
                }
                if (!StringUtils.isEmpty(entry.credits)) {
                    metadata.authors = StringUtils.append(metadata.authors, entry.credits);
                }
                if (entry.requiredMods != null) {
                    for (String s : entry.requiredMods) {
                        if (!StringUtils.isEmpty(s)) {
                            metadata.dependencies = addDependency(metadata.dependencies, s);
                        }
                    }
                }
            }
        }
    }

    private void appendClassInfo(ModMetadata metadata, InputStream stream) throws IOException {
        try {
            ClassVisitor visitor = new ModClassVisitor(metadata);
            ClassReader reader = new ClassReader(stream);
            reader.accept(visitor, 0);
        } catch (Exception e) {
            // Oh well.
        }
    }

    public ModMetadata analyze() {
        try {
            return analyze(new ZipInputStream(new FileInputStream(file)));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public ModMetadata analyze(ZipInputStream stream) {
        List<ModMetadata> recursiveMods = new ArrayList<>();
        ModMetadata metadata = new ModMetadata();
        if (isVerbose) {
            System.err.println("[*] " + file.toString());
        }

        try {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if (entry.getName().equals("mcmod.info")) {
                    appendMcmodInfo(metadata, stream);
                } else if (entry.getName().endsWith(".class")) {
                    appendClassInfo(metadata, stream);
                } else if (entry.getName().endsWith(".zip") || entry.getName().endsWith(".jar")) {
                    ModMetadata meta = Main.analyzer(null).analyze(new ZipInputStream(stream));
                    if (meta != null && meta.valid) {
                        recursiveMods.add(meta);
                    }
                } else if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    appendManifest(metadata, stream);
                }
            }
        } catch (ZipException exception) {
            return null;
        } catch (IOException exception) {
            exception.printStackTrace();
            return null;
        }

        if (!metadata.valid) {
            if (recursiveMods.size() == 1) {
                metadata = recursiveMods.get(0);
            } else if (recursiveMods.size() == 2
                    && recursiveMods.get(0).modid != null
                    && recursiveMods.get(1).modid != null
                    && recursiveMods.get(0).modid.equals(recursiveMods.get(1).modid)
                    && ((recursiveMods.get(0).side.equals("client") && recursiveMods.get(1).side.equals("server"))
                    || (recursiveMods.get(1).side.equals("client") && recursiveMods.get(0).side.equals("server")))) {
                metadata = recursiveMods.get(0);
                metadata.side = "universal";
            }
        }

        if (metadata.provides != null) {
            metadata.provides.remove(metadata.modid);
            if (metadata.provides.size() == 0) {
                metadata.provides = null;
            } else {
                metadata.valid = true;
            }
        }

        if (metadata.dependencies != null) {
            metadata.dependencies.remove(metadata.modid);
            if (metadata.provides != null) {
                for (String id : metadata.provides) {
                    metadata.dependencies.remove(id);
                }
            }
            if (metadata.dependencies.size() == 0) {
                metadata.dependencies = null;
            } else {
                metadata.valid = true;
            }
        }

        if (metadata.side == null) {
            if (metadata.dependencies != null && metadata.dependencies.containsKey("minecraft")
                    && !metadata.dependencies.get("minecraft").equals("*")) {
                boolean hasSides = MCP.hasSides(metadata.dependencies.get("minecraft"));
                if (!hasSides) {
                    metadata.side = "universal";
                }
            }
        }

        if (versionHeuristics) {
            if (metadata.side == null || metadata.dependencies == null || !metadata.dependencies.containsKey("minecraft")
                    || metadata.dependencies.get("minecraft").equals("*")) {
                Set<String> versions = new HashSet<>();
                String version;
                boolean hasClient = false, hasServer = false;
                Collection<String> heuristicVersions = MCP.getVersionsForKeySet(keys);
                if (heuristicVersions != null) {
                    for (String s : heuristicVersions) {
                        if (s.endsWith("-client")) {
                            hasClient = true;
                        } else if (s.endsWith("-server")) {
                            hasServer = true;
                        }
                        versions.add(s.split("-")[0]);
                    }

                    if (versions.size() == 1) {
                        version = (String) versions.toArray()[0];
                    } else {
                        version = Arrays.toString(versions.toArray(new String[versions.size()]));
                        version = version.replace('[', '{');
                        version = version.replace(']', '}');
                    }

                    boolean hasSides = false;
                    for (String s : versions) {
                        if (MCP.hasSides(s)) {
                            hasSides = true;
                            break;
                        }
                    }

                    String side = (!hasSides || hasClient == hasServer) ? "universal" : (hasClient ? "client" : "server");
                    metadata.valid = true;
                    metadata.side = side;
                    metadata.dependencies = addDependency(metadata.dependencies, "minecraft@" + version);
                }
            }
        }

        if (generateHash) {
            try {
                metadata.sha256 = DigestUtils.sha256Hex(new FileInputStream(file));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (storeFilenames) {
            metadata.filename = file.getName();
        }

        return metadata;
    }
}
