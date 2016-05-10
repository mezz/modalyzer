package pl.asie.modalyze;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Main {
    private static class Parameters {
        @Parameter(names = {"-H", "--hash"}, description = "Generate SHA256 hashes of mods")
        private boolean hash = false;

        @Parameter(names = {"-F", "--filename"}, description = "Store and index by mod filenames")
        private boolean filename = false;

        @Parameter(names = {"-h", "--help"}, description = "Print usage", help = true)
        private boolean help;

        @Parameter(names = {"-v", "--verbose"}, description = "Be more verbose")
        private boolean verbose;

        @Parameter(names = {"-U", "--unknown"}, description = "List unknown mod IDs and versions")
        private boolean unknown;

        @Parameter(description = "Input files and directories")
        private List<String> files = new ArrayList<>();
    }

    private static final Set<ModMetadata> modMetadata = new HashSet<>();
    private static Parameters parameters = new Parameters();

    private static ModAnalyzer analyzer(File file) {
        return new ModAnalyzer(file)
                .setVersionHeuristics(true)
                .setGenerateHash(parameters.hash)
                .setStoreFilenames(parameters.filename)
                .setIsVerbose(parameters.verbose);
    }

    public static void analyzeMods(File file) {
        if (!file.isDirectory()) {
            modMetadata.add(analyzer(file).analyze());
        } else {
            for (File f : file.listFiles()) {
                if (f.isDirectory()) {
                    analyzeMods(f);
                } else {
                    modMetadata.add(analyzer(f).analyze());
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        JCommander jCommander = new JCommander(parameters, args);
        if (parameters.help) {
            jCommander.usage();
            System.exit(0);
        }

        boolean isDir = false;
        for (String s : parameters.files) {
            File f = new File(s);
            isDir |= f.isDirectory();
            analyzeMods(f);
        }

        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.setPrettyPrinting();
        Gson gson = gsonBuilder.create();

        if (isDir || parameters.files.size() > 1 || modMetadata.size() > 1) {
            if (parameters.filename) {
                Map<String, ModMetadata> metadataMap = new HashMap<>();
                for (ModMetadata m : modMetadata) {
                    metadataMap.put(m.filename, m);
                }
                System.out.println(gson.toJson(metadataMap));
            } else {
                Map<String, Map<String, ModMetadata>> metadataMap = new HashMap<>();
                for (ModMetadata m : modMetadata) {
                    String key;
                    if (m.modid != null) {
                        key = m.modid;
                    } else if (!parameters.unknown) {
                        continue;
                    } else {
                        int i = 0;
                        while (metadataMap.containsKey("UNKNOWN-" + i)) {
                            i++;
                        }
                        key = "UNKNOWN-" + i;
                    }

                    Map<String, ModMetadata> metadataMap1 = metadataMap.get(key);
                    if (metadataMap1 == null) {
                        metadataMap1 = new HashMap<>();
                        metadataMap.put(key, metadataMap1);
                    }
                    if (m.version != null) {
                        metadataMap1.put(m.version, m);
                    } else if (parameters.unknown) {
                        int i = 0;
                        while (metadataMap1.containsKey("UNKNOWN-" + i)) {
                            i++;
                        }
                        metadataMap1.put("UNKNOWN-" + i, m);
                    }
                }
                System.out.println(gson.toJson(metadataMap));
            }
        } else if (modMetadata.size() >= 1) {
            System.out.println(gson.toJson(modMetadata.toArray()[0]));
        } else {
            System.err.println("[ERROR] No mods found!");
        }
    }
}
