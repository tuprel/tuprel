package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.model.RelationGraph;
import dev.tuprel.schema.model.ValidatedSchema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RelationGraphTest {
    private final SchemaCompiler compiler = new SchemaCompiler();

    @Test
    void pairsOneToOneOneToManyManyToManyAndSelfRelations() {
        ValidatedSchema schema = valid("""
                model User {
                    id Int @id
                    profile Profile?
                    posts Post[]
                    memberships Membership[]
                    managerId Int?
                    manager User? @relation(fields: [managerId], references: [id])
                    reports User[]
                }
                model Profile {
                    id Int @id
                    userId Int @unique
                    user User @relation(fields: [userId], references: [id])
                }
                model Post {
                    id Int @id
                    authorId Int
                    author User @relation(fields: [authorId], references: [id])
                }
                model Team {
                    id Int @id
                    memberships Membership[]
                }
                model Membership {
                    id Int @id
                    userId Int
                    teamId Int
                    user User @relation(fields: [userId], references: [id])
                    team Team @relation(fields: [teamId], references: [id])
                    @@unique([userId, teamId])
                }
                """);
        RelationGraph graph = schema.relations();
        assertTrue(graph.problems().isEmpty());
        assertEquals(new RelationGraph.RelationLink("User", "posts", "Post", RelationGraph.Side.INVERSE,
                SchemaDocument.Cardinality.LIST, List.of("id"), List.of("authorId"), Optional.of("author")),
                graph.link("User", "posts").orElseThrow());
        assertEquals(new RelationGraph.RelationLink("Post", "author", "User", RelationGraph.Side.OWNING,
                SchemaDocument.Cardinality.REQUIRED, List.of("authorId"), List.of("id"), Optional.of("posts")),
                graph.link("Post", "author").orElseThrow());
        RelationGraph.RelationLink profile = graph.link("User", "profile").orElseThrow();
        assertEquals(RelationGraph.Side.INVERSE, profile.side());
        assertFalse(profile.toMany());
        assertEquals(List.of("userId"), profile.targetFields());
        assertEquals(Optional.of("reports"), graph.link("User", "manager").orElseThrow().pairedField());
        assertEquals(List.of("managerId"), graph.link("User", "reports").orElseThrow().targetFields());
        assertEquals("Membership", graph.link("Team", "memberships").orElseThrow().target());
        assertEquals(10, graph.links().size());
    }

    @Test
    void rejectsInverseRelationsWithoutOwner() {
        assertCode("""
                model User {
                    id Int @id
                    posts Post[]
                }
                model Post {
                    id Int @id
                    authorId Int
                }
                """, "TUPREL-SCHEMA-SEM-021");
    }

    @Test
    void rejectsAmbiguousInverseRelations() {
        String twoOwners = """
                model User {
                    id Int @id
                    posts Post[]
                }
                model Post {
                    id Int @id
                    authorId Int
                    editorId Int
                    author User @relation(fields: [authorId], references: [id])
                    editor User @relation(fields: [editorId], references: [id])
                }
                """;
        assertCode(twoOwners, "TUPREL-SCHEMA-SEM-022");
        String twoInverses = """
                model User {
                    id Int @id
                    posts Post[]
                    drafts Post[]
                }
                model Post {
                    id Int @id
                    authorId Int
                    author User @relation(fields: [authorId], references: [id])
                }
                """;
        assertCode(twoInverses, "TUPREL-SCHEMA-SEM-022");
    }

    @Test
    void oneToOneInverseMustBeOptionalAndUseUniqueForeignKeys() {
        assertCode("""
                model User {
                    id Int @id
                    profile Profile
                }
                model Profile {
                    id Int @id
                    userId Int @unique
                    user User @relation(fields: [userId], references: [id])
                }
                """, "TUPREL-SCHEMA-SEM-023");
        assertCode("""
                model User {
                    id Int @id
                    profile Profile?
                }
                model Profile {
                    id Int @id
                    userId Int
                    user User @relation(fields: [userId], references: [id])
                }
                """, "TUPREL-SCHEMA-SEM-024");
    }

    @Test
    void owningRelationsWithoutInverseStayValid() {
        ValidatedSchema schema = valid("""
                model User { id Int @id }
                model Post {
                    id Int @id
                    authorId Int?
                    author User? @relation(fields: [authorId], references: [id])
                }
                """);
        assertEquals(Optional.empty(), schema.relations().link("Post", "author").orElseThrow().pairedField());
    }

    @Test
    void versionMarksOneRequiredIntegerCounterPerModel() {
        ValidatedSchema schema = valid("""
                model Product {
                    id Int @id
                    version Int @version @default(0)
                }
                """);
        assertTrue(schema.models().getFirst().fields().get(1).version());
        assertFalse(schema.models().getFirst().fields().get(0).version());
        assertCode("model Product { id Int @id version Int? @version }", "TUPREL-SCHEMA-SEM-025");
        assertCode("model Product { id Int @id version String @version }", "TUPREL-SCHEMA-SEM-025");
        assertCode("model Product { id Int @id @version }", "TUPREL-SCHEMA-SEM-025");
        assertCode("model Product { id Int @id a Int @version b Long @version }", "TUPREL-SCHEMA-SEM-026");
        assertCode("model Product { id Int @id version Int @version(1) }", "TUPREL-SCHEMA-SEM-011");
    }

    private ValidatedSchema valid(String text) {
        SchemaCompilation result = compiler.compile(SourceText.of("relations.tuprel", text));
        assertTrue(result.isValid(), () -> result.diagnostics().toString());
        return result.validatedSchema().orElseThrow();
    }

    private void assertCode(String text, String code) {
        SchemaCompilation result = compiler.compile(SourceText.of("relations.tuprel", text));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(code)),
                () -> "Expected " + code + " in " + result.diagnostics());
    }
}
