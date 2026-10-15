import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import model.ArchitectureGraph;
import model.ArchitectureKnowledge;
import model.ProjectMetadata;
import parser.ArchitectureGraphBuilder;
import parser.ArchitectureKnowledgeBuilder;
import parser.ProjectAnalyzer;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws Exception {

        ParserConfiguration configuration =
                new ParserConfiguration();

        configuration.setLanguageLevel(
                ParserConfiguration.LanguageLevel.JAVA_21);

        StaticJavaParser.setConfiguration(
                configuration);

        Path projectRoot =
                args.length > 0
                        ? Path.of(args[0])
                        : Path.of("/Users/joshi/spring-petclinic");

        Path outputPath =
                args.length > 1
                        ? Path.of(args[1])
                        : Path.of("/Users/joshi/IdeaProjects/RagParser/rag-parser-output.json");

        Path graphPath =
                outputPath.resolveSibling("rag-parser-graph.json");

        Path knowledgePath =
                outputPath.resolveSibling("rag-parser-knowledge.txt");

        ProjectMetadata project =
                ProjectAnalyzer.analyze(projectRoot);

        ArchitectureKnowledge knowledge =
                new ArchitectureKnowledgeBuilder().build(project);

        ArchitectureGraph graph =
                new ArchitectureGraphBuilder().build(project);

        project.setArchitectureKnowledge(knowledge);

        ObjectMapper mapper =
                new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT);

        mapper.writeValue(outputPath.toFile(), project);
        System.out.println("Wrote metadata to   " + outputPath.toAbsolutePath());

        mapper.writeValue(graphPath.toFile(), graph);
        System.out.println("Wrote graph to      " + graphPath.toAbsolutePath());

        Files.writeString(knowledgePath, knowledge.toReadableText());
        System.out.println("Wrote knowledge to  " + knowledgePath.toAbsolutePath());
    }
}


