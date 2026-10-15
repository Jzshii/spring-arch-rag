package parser;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class ProjectScanner {

    public static List<Path> findJavaFiles(Path projectRoot) throws IOException {

        try (Stream<Path> stream = Files.walk(projectRoot, FileVisitOption.FOLLOW_LINKS)) {

            return stream
                    .filter(path -> {
                        String s = path.toString();
                        return s.endsWith(".java")
                                && !s.contains("/test/")
                                && !s.contains("\\test\\");
                    }).toList();
        }
    }
}