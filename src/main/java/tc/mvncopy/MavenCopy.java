package tc.mvncopy;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;

/**
 * A class to create a copy of a maven project.
 */
public class MavenCopy {
    private final static String APP_NAME = MavenCopy.class.getName();
    private final static String APP_DESC = String.join("\n",
            "Copies a Maven project from a source to a destination directory,",
            "renaming the project as well.");
    private static class MavenVisitor extends SimpleFileVisitor<Path> {
        private final static Set<String> IGNORES = Set.of(".git", ".idea", ".gitignore", "target");
        private Path srcRoot;
        private Path dstRoot;
        private Map<String,String> map;
        private Pattern mapKeys;
        public MavenVisitor(Path src, Path dst, Map<String, String>table) {
            srcRoot = src;
            dstRoot = dst;
            map = table;
            mapKeys = Pattern.compile("\\b(" + String.join("|", map.keySet()) + ")\\b");
            System.err.format("Pattern: %s\n", mapKeys.toString());
        }
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String srcBase = dir.getFileName().toString();
            if (IGNORES.contains(srcBase)) {
                return SKIP_SUBTREE;
            }
            Path targetDir = dstRoot.resolve(pathSed(srcRoot.relativize(dir)));
            try {
                if (exists(targetDir.toString())) {
                    throw new IOException(targetDir.toString());
                }
                System.err.format("preVisitDirectory(%s, %s)\n", dir.toString(), targetDir.toString());
                targetDir.toFile().mkdir();
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(targetDir))
                    throw e;
            }
            return CONTINUE;
        }
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            String srcBase = file.getFileName().toString();
            if (IGNORES.contains(srcBase)) {
                return CONTINUE;
            }
            Path target = dstRoot.resolve(pathSed(srcRoot.relativize(file)));
            if (exists(target.toString())) {
                throw new IOException(target.toString());
            }
            System.err.format("visitFile(%s, %s)\n", file.toString(), target.toString());
            copyStred(file, target);
            return CONTINUE;
        }
        private Path pathSed(Path source) {
            String sourceText = source.toString();
            String targetText = sed(sourceText);
            Path target = FileSystems.getDefault().getPath(targetText);
            return target;
        }
        private void copyStred(Path source, Path target) throws IOException {
            Stream<CharSequence> outLines = Files.lines(source).map(this::sed);
            Files.write(target, outLines::iterator);
        }
        private void copySed(Path source, Path target) throws IOException {
            Charset charset = Charset.forName("UTF-8");
            try (BufferedReader br = Files.newBufferedReader(source, charset)) {
                try (BufferedWriter bw = Files.newBufferedWriter(target, charset)) {
                    String line = null;
                    while ((line = br.readLine()) != null) {
                        String outLine = sed(line) + "\n";
                        bw.write(outLine);
                    }
                }
            }
        }
        private String sed(String text) {
            StringBuffer outLine = new StringBuffer();
            Matcher matches = mapKeys.matcher(text);
            int beg = 0;
            for (beg = 0; matches.find(); beg = matches.end()) {
                outLine.append(text.substring(beg, matches.start()));
                outLine.append(map.get(matches.group()));
            }
            if (beg < text.length()) {
                outLine.append(text.substring(beg));
            }
            return outLine.toString();
        }
    }

    public boolean copy(String src, String dst, Map<String, String> table) {
        final FileSystem fs = FileSystems.getDefault();
        final Path srcPath = fs.getPath(src);
        final Path dstPath = fs.getPath(dst);
        try {
            Files.walkFileTree(srcPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,  new MavenVisitor(srcPath, dstPath, table));
        } catch (IOException e) {
            System.err.format("IO exception: %s\n", e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Constructs the transformer.
     * @param verbosity whether to be verbose about it.
     * @param noop whether to perform any changes.
     */
    public MavenCopy(boolean verbosity, boolean noop) {
        verbose = verbosity;
        dryRun = noop;
    }

    private boolean verbose = false;
    private boolean dryRun = false;

    private static boolean dirExists(String dirPath) {
        final File f = new File(dirPath);
        return f.isDirectory();
    }
    private static boolean exists(String path) {
        final File f = new File(path);
        return f.exists();
    }
    private static Map<String, String> makeTable(List<String> pairs) {
        Map<String, String> table = new HashMap<>();
        pairs.forEach((kv) -> {
            String[] parts = kv.split("=");
            if (parts.length == 2) {
                System.err.format("map: %s -> %s\n", parts[0], parts[1]);
                table.put(parts[0], parts[1]);
            }
        });
        return table;
    }

    /**
     * The command-line entry-point for the program.
     * @param argv command-line arguments.
     */
    public static void main(String[] argv) {
        ArgumentParser parser = ArgumentParsers.newFor(APP_NAME).build()
                .defaultHelp(true)
                .description(APP_DESC);
        parser.addArgument("--from").type(String.class).metavar("SOURCE")
                .dest("source").required(true)
                .help("Source project directory.");
        parser.addArgument("--to").type(String.class).metavar("DESTINATION")
                .dest("destination").required(true)
                .help("Destination project directory.");
        parser.addArgument("--map").type(String.class).metavar("OLD=NEW")
                .dest("map").action(Arguments.append())
                .help("Symbol replacement table.");
        parser.addArgument("--noop").action(Arguments.storeTrue())
                .dest("noop")
                .help("Do not execute, dry run only.");
        parser.addArgument("-v", "--verbose").action(Arguments.storeTrue())
                .dest("verbose")
                .help("Run verbosely.");
        Namespace res = null;
        try {
            res = parser.parseArgs(argv);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }
        final String src = res.getString("source");
        if (!dirExists(src)) {
            System.err.format("invalid source: %s\n", src);
            System.exit(1);
        }
        final String dst = res.getString("destination");
        if (exists(dst)) {
            System.err.format("existing destination: %s\n", dst);
            System.exit(1);
        }

        Map<String, String> table = makeTable(res.getList("map"));
        MavenCopy copier = new MavenCopy(res.getBoolean("verbose"), res.getBoolean("noop"));
        if (!copier.copy(src, dst, table)) {
            System.exit(1);
        }
        System.exit(0);
    }
}
